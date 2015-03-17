package io.cloudsoft.mssql.brooklyn;

import brooklyn.entity.Effector;
import brooklyn.entity.annotation.EffectorParam;
import brooklyn.entity.basic.ConfigKeys;
import brooklyn.entity.basic.SoftwareProcess;
import brooklyn.entity.database.DatastoreMixins;
import brooklyn.entity.proxying.ImplementedBy;
import brooklyn.event.AttributeSensor;
import brooklyn.event.basic.AttributeSensorAndConfigKey;

/**
 * Deploy Microsoft SQL Server professional editions.
 * 
 * <p>Please note:</p>
 * <ul>
 *     <li>This is for installing the professional editions of SQL Server. It is not compatible with Express
 *     Edition.</li>
 *     <li>The SQL Server installation media must be present on the remote machine (e.g. mounted CD-ROM, or files
 *     from CD-ROM copied to a local drive, or available on a network share.</li>
 *     <li>The deployment location must be configured to log in with a password. A log in without a password (such as
 *     using SSH server public key authentication) causes issues that will prevent the SQL Server installer
 *     running.</li>
 * </ul>
 */
@ImplementedBy(SqlServerProfessionalImpl.class)
public interface SqlServerProfessional extends SoftwareProcess {

    AttributeSensorAndConfigKey<String, String> INSTALL_MEDIA_PATH = ConfigKeys.newStringSensorAndConfigKey(
            "mssql.installMedia.path", "Path to the SQL Server install media", "C:\\sqlmedia");

    AttributeSensorAndConfigKey<Integer, Integer> TCP_PORT = ConfigKeys.newIntegerSensorAndConfigKey(
            "mssql.tcpPort", "SQL Server TCP port", 1433);
    AttributeSensorAndConfigKey<String, String> INSTANCE_NAME = ConfigKeys.newStringSensorAndConfigKey(
            "mssql.instanceName", "SQL Server instance name", "BROOKLYN");
    AttributeSensorAndConfigKey<String, String> SA_PASSWORD = ConfigKeys.newStringSensorAndConfigKey(
            "mssql.saPassword", "'sa' user password", null);

    AttributeSensor<String> DATASTORE_URL = DatastoreMixins.DATASTORE_URL;
    
    Effector<String> EXECUTE_SCRIPT = DatastoreMixins.EXECUTE_SCRIPT;
    String executeScript(String commands);
    
    @brooklyn.entity.annotation.Effector(description = "Adds a user to SQL Server; a password-authenticated account off the 'master' database")
    void addUser(@EffectorParam(name = "login", description = "login name for new user", nullable = false) String login,
                 @EffectorParam(name = "password", description = "password of new user", nullable = false)String password);
    
    // Convenience methods for accessing configuration information from inside the entity/driver
    String getInstallMediaPath();
    int getTcpPort();
    String getInstanceName();
    String getSaPassword();
}
