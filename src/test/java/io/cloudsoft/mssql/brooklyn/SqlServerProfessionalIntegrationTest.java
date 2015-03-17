package io.cloudsoft.mssql.brooklyn;

import static brooklyn.test.Asserts.*;
import static org.testng.Assert.*;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.Map;

import brooklyn.location.Location;
import brooklyn.util.collections.MutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import brooklyn.config.BrooklynProperties;
import brooklyn.entity.basic.ApplicationBuilder;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.management.ManagementContext;
import brooklyn.management.Task;
import brooklyn.management.internal.LocalManagementContext;
import brooklyn.test.entity.TestApplication;
import brooklyn.util.text.Strings;
import brooklyn.util.time.Duration;

/**
 * Test for the SqlServerProfessional entity.
 * 
 * <p>To use this entity, you must specify these properties:</p>
 * <ul>
 *     <li>io.cloudsoft.mssql.brooklyn.SqlServerProfessional.testMachine.address - hostname or IP address of a Windows machine to use for the test</li>
 *     <li>io.cloudsoft.mssql.brooklyn.SqlServerProfessional.testMachine.user - login name, such as Administrator</li>
 *     <li>io.cloudsoft.mssql.brooklyn.SqlServerProfessional.testMachine.password - login password</li>
 * </ul>
 */
public class SqlServerProfessionalIntegrationTest {

    public static final Logger log = LoggerFactory.getLogger(SqlServerProfessionalIntegrationTest.class);
    private static final String LOCATION_SPEC = "named:GCE US Central 1b";

    protected BrooklynProperties brooklynProperties;
    protected ManagementContext managementContext;
    protected TestApplication tapp;
    protected Location location;
    private SqlServerProfessional entity;

    @BeforeClass(alwaysRun = true)
    public void setUp() throws Exception {
        String windowsMachineAddress = System.getProperty("io.cloudsoft.mssql.brooklyn.SqlServerProfessional.testMachine.address");
        String windowsMachineUser = System.getProperty("io.cloudsoft.mssql.brooklyn.SqlServerProfessional.testMachine.user");
        String windowsMachinePassword = System.getProperty("io.cloudsoft.mssql.brooklyn.SqlServerProfessional.testMachine.password");

        brooklynProperties = BrooklynProperties.Factory.newDefault();
        managementContext = new LocalManagementContext(brooklynProperties);
        tapp = ApplicationBuilder.newManagedApp(TestApplication.class, managementContext);

        Map<String,?> allFlags = MutableMap.<String,Object>builder()
                .put("tags", ImmutableList.of(getClass().getName()))
                .build();
        location = managementContext.getLocationRegistry().resolve(LOCATION_SPEC, allFlags);

//        location = new FixedListMachineProvisioningLocation<SshMachineLocation>(ImmutableMap.of(
//                "user", windowsMachineUser,
//                "password", windowsMachinePassword,
//                "machines", ImmutableList.of(
//                        new SshMachineLocation(
//                                ImmutableMap.of(
//                                        "address", windowsMachineAddress
//                                )
//                        )
//                )
//        ));
    }

    @AfterClass(alwaysRun = true)
    public void ensureShutDown() throws Exception {
        Entities.destroyAllCatching(managementContext);
    }

    /**
     * Install SQL Server, and verify that the entity reports "service up" within 30 minutes of beginning the installation.
     * 
     * @throws Exception
     */
    @Test(groups = {"Integration"})
    public void testBasicFunctionality() throws Exception {
        EntitySpec<SqlServerProfessional> entitySpec = EntitySpec.create(SqlServerProfessional.class)
                .configure(SqlServerProfessional.INSTANCE_NAME, "TEST")
                .configure(SqlServerProfessional.SA_PASSWORD, "456r7tyghui78h463^&RFCBIW23");
        final SqlServerProfessional entity = tapp.createAndManageChild(entitySpec);
        this.entity = entity;
        tapp.start(ImmutableSet.of(location));

        // Test that we eventually get a "service up" status
        eventually(
                ImmutableMap.of("timeout", Duration.minutes(30), "Period", Duration.FIVE_SECONDS),
                new Supplier<Boolean>() {
                    @Override
                    public Boolean get() {
                        return entity.getAttribute(SqlServerProfessional.SERVICE_UP);
                    }
                },
                Predicates.equalTo(Boolean.TRUE));
        System.out.println("Done!");
    }

    /**
     * Connect to SQL Server and perform a basic operation to verify that the entity is publishing a correct URL, the
     * service is running on the expected URL, and that it is healthy.
     * 
     * <p>As a side effect, some information about the database server is logged.</p>
     * 
     * @throws Exception
     */
    @Test(groups = {"Integration"}, dependsOnMethods = {"testBasicFunctionality"})
    public void testSqlConnectivity() throws Exception {
        Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver").newInstance();
        Connection con = DriverManager.getConnection(entity.getAttribute(SqlServerProfessional.DATASTORE_URL), "sa", entity.getAttribute(SqlServerProfessional.SA_PASSWORD));
        assertNotNull(con, "JDBC connection");
        try {
            DatabaseMetaData dm = con.getMetaData();
            log.info("Driver Information");
            log.info("- Driver Name: " + dm.getDriverName());
            log.info("- Driver Version: " + dm.getDriverVersion());
            log.info("- Database Information ");
            log.info("Database Name: " + dm.getDatabaseProductName());
            log.info("- Database Version: " + dm.getDatabaseProductVersion());
            log.info("Avalilable Catalogs ");
            ResultSet rs = dm.getCatalogs();
            try {
                while (rs.next()) {
                    log.info("- catalog: " + rs.getString(1));
                }
            } finally {
                rs.close();
            }
        } finally {
            con.close();
        }
    }

    /**
     * Test that the "executeCommand" effector is working (or, at least, does not fatally error)
     */
    @Test(groups = {"Integration"}, dependsOnMethods = {"testSqlConnectivity"})
    public void testExecuteCommand() throws Exception {
        Task<String> task = entity.invoke(SqlServerProfessional.EXECUTE_SCRIPT,
                ImmutableMap.of("commands", "SELECT @@VERSION"));
        String result = task.get();
        org.testng.Assert.assertTrue(result.startsWith("Microsoft SQL Server 2008"),
                "Expected response to begin Microsoft SQL Server 2008 - is actually: " + result);
    }

    /**
     * Test add user effector. Adds a user to the database, and verifies that it is possible for the new user to
     * open a connection and perform a basic query.
     * 
     * @throws Exception
     */
    @Test(groups = {"Integration"}, dependsOnMethods = {"testExecuteCommand"})
    public void testAddUser() throws Exception {
        String login = "brooklyn_" + Strings.makeRandomId(6);
        String password = Strings.makeRandomId(12) + "_" + ( ((int)(Math.random()*100)) );
        entity.addUser(login, password);
        
        Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver").newInstance();
        Connection con = DriverManager.getConnection(entity.getAttribute(SqlServerProfessional.DATASTORE_URL), login, password);
        assertNotNull(con, "JDBC connection");
        try {
            con.getMetaData();
        } finally {
            con.close();
        }
    }

}