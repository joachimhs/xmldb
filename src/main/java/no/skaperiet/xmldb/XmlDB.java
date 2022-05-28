package no.skaperiet.xmldb;

import java.io.File;
import java.io.FilenameFilter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

import javax.naming.NamingException;
import javax.sql.DataSource;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import no.skaperiet.xmldb.query.Parameter;
import no.skaperiet.xmldb.query.Query;
import no.skaperiet.xmldb.query.QueryTree;
import org.apache.log4j.Logger;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class XmlDB {
	private Driver drv = null;
	private Connection conn = null;
	private ResultSet rs = null;
	private Statement stmt = null;
	private DataSource dataSource = null;
	QueryTree tree = null;
	private String host;
	private String db;
	private String user;
	private String pass;
	private boolean pooled = false;
	private String queryfile;
	private boolean hasResultSet = false;
	private String configFileName = "";
	private static Logger log = Logger.getLogger(XmlDB.class);

	public XmlDB(String dbName, String dbHost, String dbUser, String dbPass, String dbQueryFile) {
		this.db = dbName;
		this.host = dbHost;
		this.user = dbUser;
		this.pass = dbPass;
		this.queryfile = dbQueryFile;
		
		try {
			if (Files.isDirectory(Paths.get(dbQueryFile))) {
				File f = new File(dbQueryFile);

				FilenameFilter filter = new FilenameFilter() {
					@Override
					public boolean accept(File f, String name) {
						// We want to find only .c files
						return name.endsWith(".xml");
					}
				};

				// Note that this time we are using a File class as an array,
				// instead of String
				File[] files = f.listFiles(filter);

				for (File file : files) {
					if (tree == null) {
						log.info("creating tree from: " + file.getAbsolutePath());
						tree = new QueryTree("sqlquery", file.getAbsolutePath());
					} else {
						log.info("appending to tree from: " + file.getAbsolutePath());
						tree.appendQueriesFromFile("sqlquery", file.getAbsolutePath());
					}
				}
			} else if (Files.isRegularFile(Paths.get(dbQueryFile))) {
				log.info("Parsing XML to TreeMap");
				tree = new QueryTree("sqlquery", queryfile);
				log.info("Finished parsing XML to TreeMap");
			}

		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException("Unable to parse XMLCB Query File!");
		}

		connectDB();
	}

	public String getConfigFileName() {
		return configFileName;
	}

	public void setConfigFileName(String configFileName) {
		this.configFileName = configFileName;
	}

	public DataSource getDataSource() {
		return dataSource;
	}

	public void setDataSource(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	public String getNodeValue(Element root, String targetName) {
		NodeList elements = root.getElementsByTagName(targetName);
		if (elements.getLength() > 0)
			return ((Element) elements.item(0)).getFirstChild().getNodeValue();

		return "No value for: " + targetName;
	}

	private javax.sql.DataSource getPoolDB() throws javax.naming.NamingException {
		log.debug("Looking up DataSource: " + db);
		javax.naming.Context c = new javax.naming.InitialContext();
		return (DataSource) c.lookup(db);
	}

	public void connectDB() {
		if (pooled) {
			log.debug("Getting DB from Pool");
			try {
				if (dataSource == null) {
					dataSource = getPoolDB();
				}

				try {
					conn = dataSource.getConnection();
				} catch (SQLException e) {
					log.error("connectDB(): unable to connect to DB: " + e);
					throw new RuntimeException("XMLDB_ Unable to connect to DB");
				}
			} catch (NamingException e) {
				log.error("Unable to get Pool for XMLDB: " + e);
				throw new RuntimeException("Unable to get Pool for XMLDB");
			}
		} else {
			try {
				log.error("Creating DB Connection");
				String driver = System.getProperty("no.skaperiet.xmldb.driverClassFile");

				drv = (Driver) Class.forName(driver).newInstance();

				log.info("Driver created");
				conn = DriverManager.getConnection("jdbc:mysql://" + host + "/" + db + "?user=" + user + "&password=" + pass + "&autoreconnect=true");
			} catch (Exception e) {
				log.error("connectDB(): unable to connect to DB: " + e);
				throw new RuntimeException("XMLDB: Unable to connect to DB!");
			}
		}
	}

	public void closeConnection() {
		log.debug("Closing connection / Resetting connection back to pool");
		try {
			conn.close();
		} catch (SQLException e) {
			log.error("Unable to close connection: " + e);
		}
	}

	public boolean next() throws SQLException {
		if (hasResultSet)
			return rs.next();
		else
			return false;
	}

	public String getString(String columnName) throws Exception {
		return rs.getString(columnName);
	}

	public int getInt(String columnName) throws Exception {
		return rs.getInt(columnName);
	}

	public Date getDate(String columnName) throws Exception {
		return rs.getDate(columnName);
	}

	public double getDouble(String columnName) throws Exception {
		return rs.getDouble(columnName);
	}

	public ResultSet getResultSet() throws SQLException {
		return rs;
	}

	public Long getLong(int columnIndex) throws SQLException {
		return rs.getLong(columnIndex);
	}

	public boolean executeQuery(String name, Parameter ... parameters) {
		List<Parameter> parameterList = new ArrayList<>();
		if (parameters != null && parameters.length > 0) {
			parameterList.addAll(Arrays.asList(parameters));
		}

		return this.executeQuery(name, parameterList);
	}

	public Boolean executeUpdate(String name, Parameter ... parameters) throws SQLException {
		List<Parameter> parameterList = new ArrayList<>();
		if (parameters != null && parameters.length > 0) {
			parameterList.addAll(Arrays.asList(parameters));
		}

		return this.executeUpdate(name, parameterList);
	}

	public <T> List<T> executeQuery(String name, Class<T> clazz, Parameter ... parameters) throws SQLException {
		List<Parameter> parameterList = new ArrayList<>();
		if (parameters != null && parameters.length > 0) {
			parameterList.addAll(Arrays.asList(parameters));
		}

		return this.executeQuery(name, parameterList, clazz);
	}

	public <T> T executeQueryForId(String name, Class<T> clazz, Parameter ... parameters) throws SQLException {
		List<T> retList = executeQuery(name, clazz, parameters);

		if (retList.size() > 1) {
			throw new SQLException("Expected single record from query: " + name + " got " + retList.size());
		} else if (retList.size() == 0) {
			return null;
		}

		return retList.get(0);
	}

	private boolean executeQuery(String name, List<Parameter> parameters) {
		PreparedStatement statement = null;
		try {
			if (conn == null || conn.isClosed())
				connectDB();

			Query query = tree.getQuery(name, parameters);

			if (query != null) {
				String sql = query.getQuery();

				statement = conn.prepareStatement(sql);
				statement = setParameters(statement, parameters);
				log.debug(query.getQuery());
				log.debug("Parameters: " + parameters.toString());

				try {
					rs.close();
				} catch (Exception e) {
				}
				rs = statement.executeQuery();

				hasResultSet = rs.first();
				rs.beforeFirst();
			} else {
				hasResultSet = false;
				log.error("DBConn: executeQuery: No query matching name and params:" + name + " - " + parameters.toString());
				return false;
			}
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			try {
				// statement.close();
			} catch (Exception e) {
			}
		}

		return hasResultSet;
	}

	private <T> List<T> executeQuery(String name, List<Parameter> parameters, Class<T> clazz) throws SQLException {
		List<T> retList = new ArrayList();
		PreparedStatement statement = null;
		ResultSet resultSet = null;
		try {
			if (conn == null || conn.isClosed())
				connectDB();

			Query query = tree.getQuery(name, parameters);

			if (query != null) {
				String sql = query.getQuery();

				statement = conn.prepareStatement(sql);
				statement = setParameters(statement, parameters);
				log.debug(query.getQuery());
				log.debug("Parameters: " + parameters.toString());

				resultSet = statement.executeQuery();
				while (resultSet.next()) {
					JsonObject jsonObject = convertResultSetToJson(resultSet);
					T obj = new GsonBuilder().setDateFormat("yyyy-MM-dd HH:mm:ss").create().fromJson(jsonObject, clazz);
					if (obj != null) {
						retList.add(obj);
					}
				}
			} else {
				log.error("DBConn: executeQuery: No query matching name and params:" + name + " - " + parameters.toString());
			}
		} finally {
			try {
				resultSet.close();
				statement.close();
			} catch (Exception e) {
			}
		}

		return retList;
	}

	private JsonObject convertResultSetToJson(ResultSet rs) throws SQLException {
		ResultSetMetaData rsmd = rs.getMetaData();
		int numColumns = rsmd.getColumnCount();

		JsonObject obj = new JsonObject();

		for (int i = 1; i < numColumns + 1; i++) {
			String column_name = rsmd.getColumnName(i);
			String column_label = rsmd.getColumnLabel(i);

			if (column_label != null && column_label.length() > 0 && !column_name.equals(column_label)) {

				column_name = column_label;
			}
			if (rsmd.getColumnType(i) == java.sql.Types.BIGINT) {
				obj.addProperty(column_name, rs.getLong(column_name));
			} else if (rsmd.getColumnType(i) == java.sql.Types.REAL) {
				obj.addProperty(column_name, rs.getFloat(column_name));
			} else if (rsmd.getColumnType(i) == java.sql.Types.BOOLEAN) {
				obj.addProperty(column_name, rs.getBoolean(column_name));
			} else if (rsmd.getColumnType(i) == java.sql.Types.DOUBLE) {
				obj.addProperty(column_name, rs.getDouble(column_name));
			} else if (rsmd.getColumnType(i) == java.sql.Types.FLOAT) {
				obj.addProperty(column_name, rs.getDouble(column_name));
			} else if (rsmd.getColumnType(i) == java.sql.Types.INTEGER) {
				obj.addProperty(column_name, rs.getInt(column_name));
			} else if (rsmd.getColumnType(i) == java.sql.Types.NVARCHAR) {
				obj.addProperty(column_name, rs.getNString(column_name));
			} else if (rsmd.getColumnType(i) == java.sql.Types.VARCHAR) {
				obj.addProperty(column_name, rs.getString(column_name));
			} else if (rsmd.getColumnType(i) == java.sql.Types.CHAR) {
				obj.addProperty(column_name, rs.getString(column_name));
			} else if (rsmd.getColumnType(i) == java.sql.Types.NCHAR) {
				obj.addProperty(column_name, rs.getNString(column_name));
			} else if (rsmd.getColumnType(i) == java.sql.Types.LONGNVARCHAR) {
				obj.addProperty(column_name, rs.getNString(column_name));
			} else if (rsmd.getColumnType(i) == java.sql.Types.LONGVARCHAR) {
				obj.addProperty(column_name, rs.getString(column_name));
			} else if (rsmd.getColumnType(i) == java.sql.Types.TINYINT) {
				obj.addProperty(column_name, rs.getByte(column_name));
			} else if (rsmd.getColumnType(i) == java.sql.Types.SMALLINT) {
				obj.addProperty(column_name, rs.getShort(column_name));
			} else if (rsmd.getColumnType(i) == java.sql.Types.DATE && rs.getDate(column_name) != null) {
				obj.addProperty(column_name,  rs.getDate(column_name).toString());
			} else if (rsmd.getColumnType(i) == java.sql.Types.TIME) {
				obj.addProperty(column_name, rs.getTime(column_name).toString());
			} else if (rsmd.getColumnType(i) == java.sql.Types.TIMESTAMP && rs.getTimestamp(column_name) != null) {
				obj.addProperty(column_name, rs.getTimestamp(column_name).toString());
			} else if (rsmd.getColumnType(i) == java.sql.Types.BIT) {
				obj.addProperty(column_name, rs.getBoolean(column_name));
			} else if (rsmd.getColumnType(i) == java.sql.Types.NUMERIC) {
				obj.addProperty(column_name, rs.getBigDecimal(column_name));
			} else if (rsmd.getColumnType(i) == java.sql.Types.DECIMAL) {
				obj.addProperty(column_name, rs.getBigDecimal(column_name));
			} else {
				obj.addProperty(column_name, rs.getString(i));
			}
		}

		return obj;
	}

	private Boolean executeUpdate(String name, List<Parameter> parameters) throws SQLException {
		boolean success = false;
		PreparedStatement statement = null;
		try {
			try {
				rs.close();
			} catch (Exception e) {
			}
			if (conn == null || conn.isClosed()) {
				connectDB();
			}

			Query query = tree.getQuery(name, parameters);

			if (query != null) {
				statement = conn.prepareStatement(query.getQuery());

				statement = setParameters(statement, parameters);
				log.debug(query.getQuery());
				log.debug("Parameters: " + parameters.toString());

				hasResultSet = false;
				int numUpdatedRows = statement.executeUpdate();
				success = numUpdatedRows > 0;
			} else {
				log.error("DBConn: executeUpdate: No query matching name and params:" + name + " - " + parameters.toString());
				return null;
			}
		} finally {
			try {
				statement.close();
			} catch (Exception e) {
			}
		}

		return success;
	}

	private PreparedStatement setParameters(PreparedStatement statement, List<Parameter> parameters) throws SQLException {
		// For each parameter check it's type and set it's value in the
		// statement.
		for (int i = 0; i < parameters.size(); i++) {
			Parameter parameter = parameters.get(i);
			// log.debug("setParameters(): " + parameter.getIndex() + " - " +
			// parameter.getName() + " - " + parameter.getValue());
			Object param = parameter.getValue();
			if (param instanceof Integer) {
				statement.setInt(parameter.getIndex(), (Integer) param);
			} else if (param instanceof Long) {
				statement.setLong(parameter.getIndex(), (Long) param);
			} else if (param instanceof Timestamp) {
				statement.setTimestamp(parameter.getIndex(), (Timestamp) param);
			} else if (param instanceof Double) {
				statement.setDouble(parameter.getIndex(), (Double) param);
			} else if (param instanceof String) {
				statement.setString(parameter.getIndex(), (String) param);
			} else {
				statement.setObject(parameter.getIndex(), param);
			}
		}

		return statement;
	}

	public int numRows() throws Exception {
		rs.last();
		int pos = rs.getRow();
		rs.beforeFirst();
		return pos;
	}

	public void resetPosition() throws Exception {
		rs.beforeFirst();
	}
}