package io.cloudsoft.mssql.brooklyn;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.basic.BrooklynConfigKeys;
import brooklyn.entity.basic.SoftwareProcessImpl;
import brooklyn.entity.effector.EffectorBody;
import brooklyn.util.config.ConfigBag;
import brooklyn.util.text.Strings;

public class SqlServerProfessionalImpl extends SoftwareProcessImpl implements SqlServerProfessional {

    final static Logger logger = LoggerFactory.getLogger(SqlServerProfessionalImpl.class);
    
    @Override
    public Class<?> getDriverInterface() {
        return SqlServerProfessionalDriver.class;
    }

    @Override
    public void init() {
        super.init();

        // Brooklyn will attempt to run some scripts on the remote host, unaware that the host is running Windows and
        // cannot accept Linux bash commands. This would cause the installation to fail. Setting these config keys
        // prevents these scripts from running.
        setConfig(BrooklynConfigKeys.ONBOX_BASE_DIR, "/C/Users/Administrator/AppData/Local/Temp");
        setConfig(BrooklynConfigKeys.SKIP_ON_BOX_BASE_DIR_RESOLUTION, true);

        // If the user did not explicitly choose an 'sa' password, generate one randomly.
        if(Strings.isBlank(getConfig(SA_PASSWORD))) {
            // SQL Server passwords have rule restrictions on them - they must contain at least one item from at
            // least three of these groups:
            // Capital letters; lower case letters; digits; symbols.
            // Since makeRandomId() could conceivably randomly return all capital letters or all lower case letters,
            // there is a chance that it will not meet these rules - so we add a symbol and some digits to make sure
            // that at least three groups are represented.
            setConfig(SA_PASSWORD, Strings.makeRandomId(12) + "_" + ( ((int)(Math.random()*100)) ));
        }
        
        // Wire up the EXECUTE_SCRIPT effector to call executeScript on this class
        getMutableEntityType().addEffector(EXECUTE_SCRIPT, new EffectorBody<String>() {
            @Override
            public String call(ConfigBag parameters) {
                return executeScript((String)parameters.getStringKey("commands"));
            }
        });
        
    }

    @Override
    protected void connectSensors() {
        super.connectSensors();
        
        // Connect the default SERVICE_UP sensor - this is the isRunning() method in the driver
        connectServiceUpIsRunning();
        
        // Publish a sensor which contains the JDBC URL that can be used to access the instance
        setAttribute(DATASTORE_URL, String.format("jdbc:sqlserver://%s:%s", getAttribute(HOSTNAME), getTcpPort()));
    }

    /**
     * Execute SQL on the database
     * @param commands SQL to execute
     */
    @Override
    public String executeScript(String commands) {
        try {
            Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Microsoft JDBC driver not available", e);
        }

        try {
            String jdbcConnectionString = getAttribute(DATASTORE_URL);
            logger.debug("Opening JDBC connection to {}", jdbcConnectionString);
            Connection con = DriverManager.getConnection(jdbcConnectionString, "sa", getAttribute(SA_PASSWORD));
            try {
                logger.debug("Preparing and executing SQL statement: {}", commands);
                PreparedStatement statement = con.prepareStatement(commands);
                try {
                    boolean isResultSet = statement.execute();
                    if (isResultSet) {
                        ResultSet resultSet = statement.getResultSet();
                        if (resultSet.next()) {
                            Object object = resultSet.getObject(1);
                            return (object == null) ? "" : object.toString();
                        } else {
                            // empty result set
                            return "";
                        }
                    } else {
                        return String.valueOf(statement.getUpdateCount());
                    }
                } finally {
                    statement.close();
                }
            } finally {
                con.close();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Exception while trying to run SQL commands", e);
        }
    }

    @Override
    public void addUser(String login, String password) {
        String sql = String.format("USE master;\n" +
                " CREATE LOGIN %s with password=N'%s';\n" +
                " CREATE USER %s FOR LOGIN %s;\n" +
                "", login, password, login, login);
        executeScript(sql);
    }

    // Convenience methods for accessing configuration information from inside the entity/driver

    @Override
    public String getInstallMediaPath() {
        return getConfig(INSTALL_MEDIA_PATH);
    }

    @Override
    public int getTcpPort() {
        return getConfig(TCP_PORT);
    }

    @Override
    public String getInstanceName() {
        return getConfig(INSTANCE_NAME);
    }
    
    @Override
    public String getSaPassword() {
        return getConfig(SA_PASSWORD);
    }

}
