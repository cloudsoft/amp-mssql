SQL Server entity for AMP/Brooklyn
==================================

Simplifying assumptions
-----------------------

- SQL Server 2008 R2 on x64 is the selected version. Other versions may be usable, but this has not been tested.
- The installation media is accessible on the cloud instance - either the DVD media is mounted, or the media files
  have been copied to a local disk, or they are accessible on a network share. Testing so far has been with the media
  files available on a local drive.
- The cloud instance is accessible via SSH


Known issues
------------

The "log in" step must use a password; use of an alternative authentication method (such as using an SSH key to log
in without a password) does not correctly initialise certain Windows features that the SQL Server installer requires
(namely, the Data Protection API, DPAPI). Using an alternative authentication method will may cause installation to
fail.


How to run and test
-------------------

The entity requires the Microsoft JDBC driver, version 4. This can be downloaded from:
http://msdn.microsoft.com/en-gb/sqlserver/aa937724.aspx

Once you have obtained the `sqljdbc_4.0` package, extract it and locate `sqljdbc4.jar`. Then execute this command,
which will install the JAR into the local Maven repository:

    mvn install:install-file -Dfile=sqljdbc4.jar -DgroupId=com.microsoft.sqlserver -DartifactId=sqljdbc4 -Dversion=4.0 -Dpackaging=jar


### Integration tests

The integration tests require that a Windows host is made available for testing (a "BYON" style configuration). You
must pass system properties that provide details of the Windows host for testing, using these command line arguments:

    -Dio.cloudsoft.mssql.brooklyn.SqlServerProfessional.testMachine.address=<ip address or hostname>
    -Dio.cloudsoft.mssql.brooklyn.SqlServerProfessional.testMachine.user=<login name e.g. Administrator>
    -Dio.cloudsoft.mssql.brooklyn.SqlServerProfessional.testMachine.password=<login password>


Implementation details
----------------------

The main files in the project are as follows:

- `src/main/java/.../SqlServerProfessional.java` - the entity interface, which defines the high-level features of the
  entity
- `src/main/java/.../SqlServerProfessionalImpl.java` - the entity implementation, which provides the implementation
  of the initialisation, sensors, effectors, etc.
- `src/main/java/.../SqlServerProfessionalDriver.java` - the interface for the driver (empty)
- `src/main/java/.../SqlServerProfessionalSshDriver.java` - the driver for installing SQL Server using SSH commands
- `src/main/resources/.../ConfigurationFile.ini` - the unattended installation configuration file, in Freemarker
  template format
- `src/test/java/.../SqlServerProfessionalIntegrationTest.java` - an integration test to exercise the entity
- `blueprint-yaml/SQLServer.yaml` - an example YAML file that can be used in the Brooklyn UI.


### How to install SQL Server

After experimentation, it was determined that this is the sequence of commands that need to be executed to make a
fully-working SQL Server installation:

- Run an unattended installation of SQL Server: `setup.exe /ConfigurationFile=C:\ConfigurationFile.ini`
- Configure Windows Advanced Firewall to allow access to SQL Server's TCP/IP port:
  `netsh advfirewall firewall add rule name=SQLPort dir=in protocol=tcp action=allow localport=1433 remoteip=any profile=any`
- Configure SQL Server to activate port the TCP/IP port: (note that this is a PowerShell command):
  `( Get-WmiObject -Namespace "root\Microsoft\SqlServer\ComputerManagement10" -Query "Select * from ServerNetworkProtocolProperty where ProtocolName='Tcp' and IPAddressName='IPAll' and PropertyName='TcpPort'" ).SetStringValue("1433")`

The unattended configuration file is set to *not* activate the service, leaving it in 'manual' mode. This is because
changing the TCP/IP configuration above requires a server stop-start. To avoid confusing Brooklyn, which is
monitoring the service status, we don't want the service to start, then stop and start again to reload TCP/IP
configuration. Therefore we configure the installation to require a manual start, and Brooklyn will start the service
and set it to automatically start on boot as the final step of setup:

- Set the service to start on boot: `sc config MSSQL$TEST start= auto`
- Start the service immediately: `sc start MSSQL$TEST`

All of these steps are defined in `SqlServerProfessionalSshDriver.java`.


### Sensors

The current implementation of the entity provides only the minimum possible set of sensors. These are:

- `SERVICE_UP`. This is wired to `SqlServerProfessionalSshDriver.isRunning()`, which in turns executes an `sc query`
  command over SSH to read the status of the SQL Server service.
- `TCP_PORT`. The TCP/IP port number where the database can be reached.
- `SA_PASSWORD`. Where an explicit password was not given, a random password is generated. The password is published
  in this sensor.
- `DATASTORE_URL`. A JDBC URL where the database can be reached.


### Effectors

In addition to the usual basic effectors of "start", "stop" and "restart", the following effectors are available:

- `executeScript()` - executes arbitrary SQL as the `sa` user. Returns a single string value, which is either the
  number of rows affected by an update, or the value of the first cell in the first row of a query result set.
- `addUser()` - the "day 2" target, this effector creates a new SQL Server login with the given login name and
  password.


Future improvements
-------------------

The given entity implements the basic requirements. Suggested improvements for production are:

- *Implement 'creation script' support*. This is supported by most of Brooklyn's database entities, and allow the
  YAML blueprint to refer to an SQL script which is run as part of the setup procedure.
- *Implement the `DatastoreMixin` interface*. This defines common behavior of database entities. As this version does
  not implement creation scripts, it was not possible to implement this interface - however once that task is done,
  the entity can be modified to implement `DatastoreMixin` and therefore appear more like other database entities.
- Support more installation options. There are few installation options currently support in this entity, but by
  modifying ConfigurationFile.ini, many more options are possible.
- Support more versions of SQL Server. The installation process for all recent versions of SQL Server are broadly
  similar and so could be supported by Brooklyn, for example by specifying a config key which causes a different
  ConfigurationFile.ini to be used.
- Address the "simplifying assumptions" to make a more general-purpose blueprint.
