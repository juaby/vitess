package com.flipkart.vitess.jdbc;

import com.flipkart.vitess.util.Constants;
import com.flipkart.vitess.util.StringUtils;
import com.youtube.vitess.client.Context;
import com.youtube.vitess.client.VTGateConn;
import com.youtube.vitess.client.VTGateTx;
import com.youtube.vitess.client.cursor.Cursor;
import com.youtube.vitess.mysql.DateTime;
import com.youtube.vitess.proto.Topodata;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.sql.Date;
import java.util.*;
import java.util.logging.Logger;

/**
 * Created by harshit.gangal on 25/01/16.
 */
public class VitessPreparedStatement extends VitessStatement implements PreparedStatement {

    /* Get actual class name to be printed on */
    private static Logger logger = Logger.getLogger(VitessPreparedStatement.class.getName());
    private final String sql;
    private final Map<String, Object> bindVariables;

    public VitessPreparedStatement(VitessConnection vitessConnection, String sql) throws SQLException {
        this(vitessConnection, sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
    }

    public VitessPreparedStatement(VitessConnection vitessConnection, String sql, int resultSetType,
                                   int resultSetConcurrency) throws SQLException {
        super(vitessConnection, resultSetType, resultSetConcurrency);
        checkSQLNullOrEmpty(sql);
        this.bindVariables = new HashMap<>();
        this.sql = sql;
    }

    public ResultSet executeQuery() throws SQLException {
        VTGateConn vtGateConn;
        Topodata.TabletType tabletType;
        Cursor cursor;
        boolean showSql;

        checkOpen();
        closeOpenResultSetAndResetCount();

        vtGateConn = this.vitessConnection.getVtGateConn();
        tabletType = this.vitessConnection.getTabletType();

        showSql = StringUtils.startsWithIgnoreCaseAndWs(sql, Constants.SQL_SHOW);
        if (showSql) {
            String keyspace = this.vitessConnection.getKeyspace();
            List<byte[]> keyspaceIds = Arrays.asList(new byte[]{1}); //To Hit any single shard

            Context context = this.vitessConnection.createContext(this.queryTimeoutInMillis);
            cursor = vtGateConn
                .executeKeyspaceIds(context, this.sql, keyspace, keyspaceIds, null, tabletType)
                .checkedGet();
        } else {
            if (tabletType != Topodata.TabletType.MASTER || this.vitessConnection.getAutoCommit()) {
                Context context =
                    this.vitessConnection.createContext(this.queryTimeoutInMillis);
                cursor = vtGateConn.execute(context, this.sql, this.bindVariables, tabletType)
                    .checkedGet();
            } else {
                VTGateTx vtGateTx = this.vitessConnection.getVtGateTx();
                if (vtGateTx == null) {
                    Context context =
                        this.vitessConnection.createContext(this.queryTimeoutInMillis);
                    vtGateTx = vtGateConn.begin(context).checkedGet();
                    this.vitessConnection.setVtGateTx(vtGateTx);
                }
                Context context = this.vitessConnection.createContext(this.queryTimeoutInMillis);
                cursor = executeSQL(vtGateTx, context, this.sql, tabletType,
                    this.bindVariables);
            }
        }

        if (null == cursor) {
            throw new SQLException(Constants.SQLExceptionMessages.METHOD_CALL_FAILED);
        }

        this.vitessResultSet = new VitessResultSet(cursor, this);
        return (this.vitessResultSet);
    }

    public int executeUpdate() throws SQLException {
        VTGateConn vtGateConn;
        Topodata.TabletType tabletType;
        Cursor cursor;

        checkOpen();
        closeOpenResultSetAndResetCount();

        vtGateConn = this.vitessConnection.getVtGateConn();
        tabletType = this.vitessConnection.getTabletType();

        if (tabletType != Topodata.TabletType.MASTER) {
            throw new SQLException(Constants.SQLExceptionMessages.DML_NOT_ON_MASTER);
        }

        VTGateTx vtGateTx = this.vitessConnection.getVtGateTx();
        if (vtGateTx == null) {
            Context context = this.vitessConnection.createContext(this.queryTimeoutInMillis);
            vtGateTx = vtGateConn.begin(context).checkedGet();
            this.vitessConnection.setVtGateTx(vtGateTx);
        }

        if (this.vitessConnection.getAutoCommit()) {
            Context context = this.vitessConnection.createContext(this.queryTimeoutInMillis);
            cursor = executeSQL(vtGateTx, context, this.sql, tabletType,
                this.bindVariables);
            vtGateTx.commit(context).checkedGet();
            vtGateTx = null;
            this.vitessConnection.setVtGateTx(null);
        } else {
            Context context = this.vitessConnection.createContext(this.queryTimeoutInMillis);
            cursor = executeSQL(vtGateTx, context, this.sql, tabletType,
                this.bindVariables);
        }

        if (null == cursor) {
            throw new SQLException(Constants.SQLExceptionMessages.METHOD_CALL_FAILED);
        }

        if (!(null == cursor.getFields() || cursor.getFields().isEmpty())) {
            throw new SQLException(Constants.SQLExceptionMessages.SQL_RETURNED_RESULT_SET);
        }

        this.resultCount = cursor.getRowsAffected();

        int truncatedUpdateCount;

        if (this.resultCount > Integer.MAX_VALUE) {
            truncatedUpdateCount = Integer.MAX_VALUE;
        } else {
            truncatedUpdateCount = (int) this.resultCount;
        }
        return truncatedUpdateCount;
    }

    public boolean execute() throws SQLException {
        VTGateConn vtGateConn;
        Topodata.TabletType tabletType;
        Cursor cursor;
        boolean selectSql;
        boolean showSql;

        checkOpen();
        closeOpenResultSetAndResetCount();

        vtGateConn = this.vitessConnection.getVtGateConn();
        tabletType = this.vitessConnection.getTabletType();

        selectSql = StringUtils.startsWithIgnoreCaseAndWs(this.sql, Constants.SQL_SELECT);
        showSql = StringUtils.startsWithIgnoreCaseAndWs(sql, Constants.SQL_SHOW);

        if (selectSql) {

            Context context = this.vitessConnection.createContext(this.queryTimeoutInMillis);
            cursor =
                vtGateConn.execute(context, this.sql, this.bindVariables, tabletType).checkedGet();
        } else if (showSql) {
            String keyspace = this.vitessConnection.getKeyspace();
            List<byte[]> keyspaceIds = Arrays.asList(new byte[]{1}); //To Hit any single shard

            Context context = this.vitessConnection.createContext(this.queryTimeoutInMillis);

            cursor = vtGateConn
                .executeKeyspaceIds(context, this.sql, keyspace, keyspaceIds, null, tabletType)
                .checkedGet();
        } else {
            VTGateTx vtGateTx = this.vitessConnection.getVtGateTx();
            if (null == vtGateTx) {
                Context context = this.vitessConnection.createContext(this.queryTimeoutInMillis);
                vtGateTx = vtGateConn.begin(context).checkedGet();
                this.vitessConnection.setVtGateTx(vtGateTx);
            }

            Context context = this.vitessConnection.createContext(this.queryTimeoutInMillis);
            cursor = executeSQL(vtGateTx, context, this.sql, tabletType,
                this.bindVariables);

            if (this.vitessConnection.getAutoCommit()) {
                context = this.vitessConnection.createContext(this.queryTimeoutInMillis);
                vtGateTx.commit(context).checkedGet();
                this.vitessConnection.setVtGateTx(null);
            }
        }

        if (null == cursor) {
            throw new SQLException(Constants.SQLExceptionMessages.METHOD_CALL_FAILED);
        }

        if (!(null == cursor.getFields() || cursor.getFields().isEmpty())) {
            this.vitessResultSet = new VitessResultSet(cursor, this);
            return true;
        } else {
            this.resultCount = cursor.getRowsAffected();
            return false;
        }
    }

    public void clearParameters() throws SQLException {
        checkOpen();
        this.bindVariables.clear();
    }

    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        checkOpen();
        this.bindVariables.put(Constants.LITERAL_V + parameterIndex, null);
    }

    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        checkOpen();
        this.bindVariables.put(Constants.LITERAL_V + parameterIndex, x);
    }

    public void setByte(int parameterIndex, byte x) throws SQLException {
        checkOpen();
        this.bindVariables.put(Constants.LITERAL_V + parameterIndex, x);
    }

    public void setShort(int parameterIndex, short x) throws SQLException {
        checkOpen();
        this.bindVariables.put(Constants.LITERAL_V + parameterIndex, x);
    }

    public void setInt(int parameterIndex, int x) throws SQLException {
        checkOpen();
        this.bindVariables.put(Constants.LITERAL_V + parameterIndex, x);
    }

    public void setLong(int parameterIndex, long x) throws SQLException {
        checkOpen();
        this.bindVariables.put(Constants.LITERAL_V + parameterIndex, x);
    }

    public void setFloat(int parameterIndex, float x) throws SQLException {
        checkOpen();
        this.bindVariables.put(Constants.LITERAL_V + parameterIndex, x);
    }

    public void setDouble(int parameterIndex, double x) throws SQLException {
        checkOpen();
        this.bindVariables.put(Constants.LITERAL_V + parameterIndex, x);
    }

    public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        checkOpen();
        this.bindVariables.put(Constants.LITERAL_V + parameterIndex, x);
    }

    public void setString(int parameterIndex, String x) throws SQLException {
        checkOpen();
        this.bindVariables.put(Constants.LITERAL_V + parameterIndex, x);
    }

    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        checkOpen();
        this.bindVariables.put(Constants.LITERAL_V + parameterIndex, x);
    }

    public void setDate(int parameterIndex, Date x) throws SQLException {
        checkOpen();
        String date = DateTime.formatDate(x);
        this.bindVariables.put(Constants.LITERAL_V + parameterIndex, date);
    }

    public void setTime(int parameterIndex, Time x) throws SQLException {
        checkOpen();
        String time = DateTime.formatTime(x);
        this.bindVariables.put(Constants.LITERAL_V + parameterIndex, time);
    }

    public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
        checkOpen();
        String timeStamp = DateTime.formatTimestamp(x);
        this.bindVariables.put(Constants.LITERAL_V + parameterIndex, timeStamp);
    }

    public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
        checkOpen();
        String date = DateTime.formatDate(x, cal);
        this.bindVariables.put(Constants.LITERAL_V + parameterIndex, date);
    }

    public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
        checkOpen();
        String time = DateTime.formatTime(x, cal);
        this.bindVariables.put(Constants.LITERAL_V + parameterIndex, time);
    }

    public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
        checkOpen();
        String timeStamp = DateTime.formatTimestamp(x, cal);
        this.bindVariables.put(Constants.LITERAL_V + parameterIndex, timeStamp);
    }

    public void setObject(int parameterIndex, Object x) throws SQLException {
        if (x == null) {
            setNull(parameterIndex, Types.NULL);
        } else if (x instanceof String) {
            setString(parameterIndex, (String) x);
        } else if (x instanceof Short) {
            setShort(parameterIndex, (Short) x);
        } else if (x instanceof Integer) {
            setInt(parameterIndex, (Integer) x);
        } else if (x instanceof Long) {
            setLong(parameterIndex, (Long) x);
        } else if (x instanceof Float) {
            setFloat(parameterIndex, (Float) x);
        } else if (x instanceof Double) {
            setDouble(parameterIndex, (Double) x);
        } else if (x instanceof Boolean) {
            setBoolean(parameterIndex, (Boolean) x);
        } else if (x instanceof Byte) {
            setByte(parameterIndex, (Byte) x);
        } else if (x instanceof Character) {
            setString(parameterIndex, String.valueOf(x));
        } else if (x instanceof Date) {
            setDate(parameterIndex, (Date) x);
        } else if (x instanceof Time) {
            setTime(parameterIndex, (Time) x);
        } else if (x instanceof Timestamp) {
            setTimestamp(parameterIndex, (Timestamp) x);
        } else if (x instanceof BigDecimal) {
            setBigDecimal(parameterIndex, (BigDecimal) x);
        } else {
            throw new SQLException(
                Constants.SQLExceptionMessages.SQL_TYPE_INFER + x.getClass().getCanonicalName());
        }
    }

    // Methods Private to this class

    private Cursor executeSQL(VTGateTx vtGateTx,
                              final Context context, final String sql, final Topodata.TabletType tabletType,
                              final Map<String, Object> bindVariables)
        throws SQLException {

        return vtGateTx.execute(context, sql, bindVariables, tabletType).checkedGet();

    }

    //Methods which are currently not supported

    public void addBatch() throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public ParameterMetaData getParameterMetaData() throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setCharacterStream(int parameterIndex, Reader reader, int length)
        throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength)
        throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setBinaryStream(int parameterIndex, InputStream x, long length)
        throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setCharacterStream(int parameterIndex, Reader reader, long length)
        throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setUnicodeStream(int parameterIndex, InputStream x, int length)
        throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setRef(int parameterIndex, Ref x) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setBlob(int parameterIndex, Blob x) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setClob(int parameterIndex, Clob x) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setArray(int parameterIndex, Array x) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public ResultSetMetaData getMetaData() throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setURL(int parameterIndex, URL x) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setRowId(int parameterIndex, RowId x) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setNString(int parameterIndex, String value) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setNCharacterStream(int parameterIndex, Reader value, long length)
        throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setNClob(int parameterIndex, NClob value) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setBlob(int parameterIndex, InputStream inputStream, long length)
        throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setClob(int parameterIndex, Reader reader) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setNClob(int parameterIndex, Reader reader) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }
}
