package io.cloudsoft.mssql.brooklyn;

import java.nio.charset.Charset;

import org.apache.commons.codec.binary.Base64;

import com.google.common.collect.ImmutableList;

import brooklyn.entity.basic.AbstractSoftwareProcessSshDriver;
import brooklyn.entity.basic.EntityLocal;
import brooklyn.location.basic.SshMachineLocation;

public class SqlServerProfessionalSshDriver extends AbstractSoftwareProcessSshDriver implements SqlServerProfessionalDriver {

    public SqlServerProfessionalSshDriver(EntityLocal entity, SshMachineLocation machine) {
        super(entity, machine);
    }

    private SqlServerProfessional getSqlServerEntity() {
        return (SqlServerProfessional) getEntity();
    }

    @Override
    public void install() {
        // Install the unattended installation configuration. copyTemplate causes the resource to be parsed as a
        // FreeMarker template, which is able to substitute configuration variables in. For example, the SQL Server
        // instance name will be substituted into the file as it is being copied to the installation host.
        copyTemplate("classpath://io/cloudsoft/mssql/brooklyn/ConfigurationFile.ini", "/C/ConfigurationFile.ini");

        // Run the unattended install command
        runWindowsCommand("Install", String.format("%ssetup.exe /ConfigurationFile=C:\\ConfigurationFile.ini", getSqlServerEntity().getInstallMediaPath()));
    }

    @Override
    public void customize() {
        // Get important configuration information from the entity
        int tcpPort = getSqlServerEntity().getTcpPort();

        // Configure SQL Server to open the TCP/IP port, and configure Windows Advanced Firewall to allow access
        runWindowsCommand("Open firewall port", "netsh advfirewall firewall add rule name=SQLPort dir=in protocol=tcp action=allow localport=" + tcpPort + " remoteip=any profile=any");
        runWindowsPowerShellCommand("Enable TCP/IP port", "( Get-WmiObject -Namespace \"root\\Microsoft\\SqlServer\\ComputerManagement10\" -Query \"Select * from ServerNetworkProtocolProperty where ProtocolName='Tcp' and IPAddressName='IPAll' and PropertyName='TcpPort'\" ).SetStringValue(\"" + tcpPort + "\")");
    }

    @Override
    public void launch() {
        // Get important configuration information from the entity
        String instanceName = getSqlServerEntity().getInstanceName();
        String serviceName = "MSSQL$" + instanceName;

        // The unattended configuration should have *not* started the service, leaving it in 'manual' mode. This is
        // because changing the TCP/IP configuration above requires a server stop-start. To avoid confusing Brooklyn,
        // which is monitoring the service status, we don't want the service to start, then stop and start again to
        // reload TCP/IP configuration. Therefore we configure the installation to require a manual start, and we
        // will take responsibility for starting the service and setting it to automatically start on boot.
        runWindowsCommandIgnoringError("Stop service", String.format("sc stop %s", serviceName));
        runWindowsCommand("Set service to auto start", String.format("sc config %s start= auto", serviceName));
        runWindowsCommand("Start service", String.format("sc start %s", serviceName));
    }

    @Override
    public void stop() {
        // Get important configuration information from the entity
        String instanceName = getSqlServerEntity().getInstanceName();
        String serviceName = "MSSQL$" + instanceName;

        runWindowsCommand("Set service to manual start", String.format("sc config %s start= demand", serviceName));
        runWindowsCommand("Stop service", String.format("sc stop %s", serviceName));
    }

    @Override
    public boolean isRunning() {
        // Get important configuration information from the entity
        String instanceName = getSqlServerEntity().getInstanceName();
        String serviceName = "MSSQL$" + instanceName;

        // Run an SSH command to query the state of the SQL Server service - return true if it is RUNNING

        String queryCommand = String.format("sc query \"%s\" | find \"RUNNING\"", serviceName);
        int errorlevel = getMachine().execCommands("Query service status", ImmutableList.of(queryCommand));
        // ERRORLEVEL of 0 means found, 1 not found
        return errorlevel == 0;
    }

    @Override
    public void copyInstallResources() {
        // The base class implementation of this method is not Windows compatible - it will attempt to run commands
        // that are only valid on Linux-like systems and will cause failures on Windows. Therefore we override this
        // method to do nothing.
    }

    @Override
    public void copyRuntimeResources() {
        // The base class implementation of this method is not Windows compatible - it will attempt to run commands
        // that are only valid on Linux-like systems and will cause failures on Windows. Therefore we override this
        // method to do nothing.
    }

    // Unlike most other entities, we are unable to use "newScript(INSTALLING)..." style of instructions to run
    // commands on the installation host - those style of instructions add additional commands to the script which
    // expect a Linux-like installation host, and cause failures on Windows hosts. Therefore we use lower-level
    // methods which do not modify the commands we want to run. For convenience, these are encapsulated here.

    private void runWindowsCommand(String summaryForLogging, String command) {
        log.info(command);
        int errorlevel = getMachine().execCommands(summaryForLogging, ImmutableList.of(command));
        if (errorlevel != 0)
            throw new RuntimeException(summaryForLogging + ": command failed with errorlevel " + errorlevel);
    }

    private void runWindowsCommandIgnoringError(String summaryForLogging, String command) {
        log.info(command);
        getMachine().execCommands(summaryForLogging, ImmutableList.of(command));
    }

    private void runWindowsPowerShellCommand(String summaryForLogging, String command) {
        String setPortPSCmd = "PowerShell -EncodedCommand " + Base64.encodeBase64String(command.getBytes(Charset.forName("UTF-16LE")));
        runWindowsCommand(summaryForLogging, setPortPSCmd);
    }
}
