package com.pm.stack;

import com.amazonaws.services.kafka.model.KafkaVersion;
import software.amazon.awscdk.*;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.ecs.Protocol;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedFargateService;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.msk.CfnCluster;
import software.amazon.awscdk.services.rds.*;
import software.amazon.awscdk.services.route53.CfnHealthCheck;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class LocalStack extends Stack {

    private final Vpc vpc;
    private final Cluster ecsCluster;

    public LocalStack(final App scope, final String id, final StackProps props) {
        super(scope, id, props);
        this.vpc = createVpc();

        DatabaseInstance keycloakDB = createDatabase("KeycloakDB", "keycloak-db");


        DatabaseInstance patientServiceDb = createDatabase("PatientServiceDB", "patient-service-db");

        CfnHealthCheck keycloakDbHealthCheck = createDbHealthCheck(keycloakDB, "KeycloakDBHealthCheck");

        CfnHealthCheck patientServiceDbHealthCheck = createDbHealthCheck(patientServiceDb, "PatientServiceDBHealthCheck");

        CfnCluster mskCluster = createMskCluster();

        this.ecsCluster = createEcsCluster();

        FargateService keycloakService = createKeycloakService(keycloakDB);

        keycloakService.getNode().addDependency(keycloakDbHealthCheck);
        keycloakService.getNode().addDependency(keycloakDB);

        FargateService billingService = createFargateService(
                "BillingService",
                "billing-service",
                List.of(4001, 9001),
                null,
                null
        );

        FargateService analysisService = createFargateService(
                "AnalyticsService",
                "analytics-service",
                List.of(4002),
                null,
                null
        );

        analysisService.getNode().addDependency(mskCluster);

        FargateService patientService = createFargateService(
                "PatientService",
                "patient-service",
                List.of(4000),
                patientServiceDb,
                Map.of(
                        "BILLING_SERVICE_ADDRESS", "host.docker.internal",
                        "BILLING_SERVICE_GRPC_PORT", "9001"
                )
        );

        patientService.getNode().addDependency(patientServiceDbHealthCheck);
        patientService.getNode().addDependency(patientServiceDb);
        patientService.getNode().addDependency(billingService);
        patientService.getNode().addDependency(mskCluster);

        createApiGatewayService();
    }

    private Vpc createVpc() {
        return Vpc.Builder
                .create(this, "PatientManagementVPC")
                .vpcName("PatientManagementVPC")
                .maxAzs(2)
                .build();
    }

    private DatabaseInstance createDatabase(String id, String dbName) {
        return DatabaseInstance.Builder
                .create(this, id)
                .engine(DatabaseInstanceEngine.postgres(
                                PostgresInstanceEngineProps.builder()
                                        .version(PostgresEngineVersion.VER_18)
                                        .build()
                        )
                )
                .vpc(vpc)
                .instanceType(InstanceType.of(InstanceClass.BURSTABLE2, InstanceSize.MICRO))
                .allocatedStorage(20)
                .credentials(Credentials.fromGeneratedSecret("admin_user"))
                .databaseName(dbName)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();
    }

    private CfnHealthCheck createDbHealthCheck(DatabaseInstance db, String id) {
        return CfnHealthCheck.Builder
                .create(this, id)
                .healthCheckConfig(
                        CfnHealthCheck.HealthCheckConfigProperty.builder()
                                .type("TCP")
                                .port(Token.asNumber(db.getDbInstanceEndpointPort()))
                                .ipAddress(db.getDbInstanceEndpointAddress())
                                .requestInterval(30)
                                .failureThreshold(3)
                                .build()
                )
                .build();
    }

    private CfnCluster createMskCluster() {
        return CfnCluster.Builder
                .create(this, "MskCluster")
                .clusterName("kafka-cluster")
                .kafkaVersion("3.5.1")
                .numberOfBrokerNodes(2)
                .brokerNodeGroupInfo(
                        CfnCluster.BrokerNodeGroupInfoProperty.builder()
                                .instanceType("kafka.m5.xlarge")
                                .clientSubnets(
                                        vpc.getPrivateSubnets().stream()
                                                .map(ISubnet::getSubnetId)
                                                .collect(Collectors.toList())
                                )
                                .brokerAzDistribution("DEFAULT")
                                .build()
                )
                .build();
    }

    // example: keycloak.patient-management.local or patient-service.patient-management.local
    private Cluster createEcsCluster() {
        return Cluster.Builder
                .create(this, "PatientManagementCluster")
                .vpc(vpc)
                .defaultCloudMapNamespace(
                        CloudMapNamespaceOptions.builder()
                                .name("patient-management.local")
                                .build()
                )
                .build();
    }

    private FargateService createKeycloakService(DatabaseInstance db) {

        FargateTaskDefinition taskDefinition = FargateTaskDefinition.Builder
                .create(this, "KeycloakTaskDefinition")
                .cpu(512)
                .memoryLimitMiB(1024)
                .build();

        LogGroup logGroup = LogGroup.Builder.create(this, "KeycloakLogGroup")
                .logGroupName("/ecs/keycloak-service")
                .retention(RetentionDays.ONE_DAY)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        ContainerDefinitionOptions containerOptions =
                ContainerDefinitionOptions.builder()
                        .image(ContainerImage.fromRegistry("keycloak-service"))
                        .command(List.of("start-dev")) // REQUIRED
                        .portMappings(List.of(
                                PortMapping.builder()
                                        .containerPort(8080)
                                        .hostPort(8080)
                                        .protocol(Protocol.TCP)
                                        .build()
                        ))
                        .environment(Map.of(
                                "KC_BOOTSTRAP_ADMIN_USERNAME", "admin",
                                "KC_BOOTSTRAP_ADMIN_PASSWORD", "admin",
                                "KC_DB", "postgres",
                                "KC_DB_URL", String.format(
                                        "jdbc:postgresql://%s:%s/keycloak-db",
                                        db.getDbInstanceEndpointAddress(),
                                        db.getDbInstanceEndpointPort()
                                ),
                                "KC_DB_USERNAME", "admin_user",
//                                "KC_HTTP_ENABLED", "true",
                                "KC_HOSTNAME", "http://keycloak-service.patient-management.local:8080"
//                                "KC_HOSTNAME_STRICT", "false"
                        ))
                        .secrets(Map.of(
                                "KC_DB_PASSWORD",
                                Secret.fromSecretsManager(db.getSecret(), "password")
                        ))
                        .logging(LogDrivers.awsLogs(AwsLogDriverProps.builder()
                                .logGroup(logGroup)
                                .streamPrefix("keycloak")
                                .build()))
                        .build();

        taskDefinition.addContainer("KeycloakContainer", containerOptions);

        return FargateService.Builder
                .create(this, "KeycloakService")
                .cluster(ecsCluster)
                .taskDefinition(taskDefinition)
                .serviceName("keycloak-service")
                .assignPublicIp(true) // important for LocalStack unless NAT configured
                .desiredCount(1)
                .build();
    }

    private FargateService createFargateService(
            String id,
            String imageName,
            List<Integer> ports,
            DatabaseInstance db,
            Map<String, String> additionalEnvVars
    ) {
        FargateTaskDefinition taskDefinition = FargateTaskDefinition.Builder
                .create(this, id + "Task")
                .cpu(256)
                .memoryLimitMiB(512)
                .build();

        ContainerDefinitionOptions.Builder containerOptions = ContainerDefinitionOptions.builder()
                .image(ContainerImage.fromRegistry(imageName)) // In real production use ECR or DockerHub, for localstack we can use local images
                .portMappings(ports.stream()
                        .map(port -> PortMapping.builder()
                                .containerPort(port)
                                .hostPort(port)
                                .protocol(Protocol.TCP)
                                .build())
                        .collect(Collectors.toList())
                )
                .logging(LogDrivers.awsLogs(AwsLogDriverProps.builder()
                        .logGroup(LogGroup.Builder.create(this, id + "LogGroup")
                                .logGroupName("/ecs/" + imageName)
                                .removalPolicy(RemovalPolicy.DESTROY)
                                .retention(RetentionDays.ONE_DAY)
                                .build())
                        .streamPrefix(imageName)
                        .build())
                );

        Map<String, String> envVars = new HashMap<>();
        envVars.put("SPRING_KAFKA_BOOTSTRAP_SERVERS", "localhost.localstack.cloud:4510, localhost.localstack.cloud:4511, localhost.localstack.cloud:4512"); // LocalStack MSK endpoint

        if (additionalEnvVars != null) {
            envVars.putAll(additionalEnvVars);
        }

        if (db != null) {
            envVars.put("SPRING_DATASOURCE_URL", "jdbc:postgresql://%s:%s/%s-db".formatted(
                            db.getDbInstanceEndpointAddress(),
                            db.getDbInstanceEndpointPort(),
                            imageName
                    )
            );
            envVars.put("SPRING_DATASOURCE_USERNAME", "admin_user");
            envVars.put("SPRING_DATASOURCE_PASSWORD", db.getSecret().secretValueFromJson("password").toString());
            envVars.put("SPRING_JPA_HIBERNATE_DDL_AUTO", "update");
            envVars.put("SPRING_SQL_INIT_MODE", "always");
            envVars.put("SPRING_DATASOURCE_HIKARI_INILITIALIZATION_FAIL_TIMEOUT", "60000");
        }

        containerOptions.environment(envVars);

        String sanitizedImageName = imageName.replaceAll("[^a-zA-Z0-9-]", "-");
        taskDefinition.addContainer(sanitizedImageName + "Container", containerOptions.build());

        return FargateService.Builder
                .create(this, id)
                .cluster(ecsCluster)
                .taskDefinition(taskDefinition)
                .assignPublicIp(false) // Using private subnets, so no public IP
                .serviceName(imageName)
                .build();
    }

    private void createApiGatewayService() {
        FargateTaskDefinition taskDefinition = FargateTaskDefinition.Builder
                .create(this, "ApiGatewayTaskDefinition")
                .cpu(256)
                .memoryLimitMiB(512)
                .build();

        ContainerDefinitionOptions containerOptions = ContainerDefinitionOptions.builder()
                .image(ContainerImage.fromRegistry("api-gateway")) // In real production use ECR or DockerHub, for localstack we can use local images
                .environment(Map.of(
                        "SPRING_PROFILES_ACTIVE", "prod",
                        "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI", "http://keycloak-service.patient-management.local:8080/realms/patient-management"
                ))
                .portMappings(List.of(4004).stream()
                        .map(port -> PortMapping.builder()
                                .containerPort(port)
                                .hostPort(port)
                                .protocol(Protocol.TCP)
                                .build())
                        .collect(Collectors.toList())
                )
                .logging(LogDrivers.awsLogs(AwsLogDriverProps.builder()
                        .logGroup(LogGroup.Builder.create(this, "ApiGatewayLogGroup")
                                .logGroupName("/ecs/api-gateway")
                                .removalPolicy(RemovalPolicy.DESTROY)
                                .retention(RetentionDays.ONE_DAY)
                                .build())
                        .streamPrefix("api-gateway")
                        .build())
                )
                .build();

        taskDefinition.addContainer("ApiGatewayContainer", containerOptions);

        ApplicationLoadBalancedFargateService.Builder
                .create(this, "ApiGatewayService")
                .cluster(ecsCluster)
                .serviceName("api-gateway")
                .taskDefinition(taskDefinition)
                .desiredCount(1)
                .healthCheckGracePeriod(Duration.seconds(60))
                .build();
    }

    public static void main(String[] args) {
        App app = new App(
                AppProps.builder()
                        .outdir("./cdk.out")
                        .build()
        );

        StackProps props = StackProps.builder()
                .synthesizer(new BootstraplessSynthesizer())
                .build();

        new LocalStack(app, "localstack", props);
        app.synth();
        System.out.println("App synthesizing in progress...");
    }
}
