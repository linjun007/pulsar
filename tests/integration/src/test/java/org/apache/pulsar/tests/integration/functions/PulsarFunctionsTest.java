/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.tests.integration.functions;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import com.google.common.base.Stopwatch;
import com.google.gson.Gson;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.client.impl.schema.AvroSchema;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.functions.api.examples.AutoSchemaFunction;
import org.apache.pulsar.functions.api.examples.serde.CustomObject;
import org.apache.pulsar.tests.integration.docker.ContainerExecException;
import org.apache.pulsar.tests.integration.docker.ContainerExecResult;
import org.apache.pulsar.tests.integration.functions.utils.CommandGenerator;
import org.apache.pulsar.tests.integration.functions.utils.CommandGenerator.Runtime;
import org.apache.pulsar.tests.integration.io.*;
import org.apache.pulsar.tests.integration.io.JdbcSinkTester.Foo;
import org.apache.pulsar.tests.integration.topologies.FunctionRuntimeType;
import org.apache.pulsar.tests.integration.topologies.PulsarCluster;
import org.testcontainers.containers.GenericContainer;
import org.testng.annotations.Test;

/**
 * A test base for testing sink.
 */
@Slf4j
public abstract class PulsarFunctionsTest extends PulsarFunctionsTestBase {

    PulsarFunctionsTest(FunctionRuntimeType functionRuntimeType) {
        super(functionRuntimeType);
    }

    @Test
    public void testKafkaSink() throws Exception {
        testSink(new KafkaSinkTester(), true, new KafkaSourceTester());
    }

    @Test(enabled = false)
    public void testCassandraSink() throws Exception {
        testSink(CassandraSinkTester.createTester(true), true);
    }

    @Test(enabled = false)
    public void testCassandraArchiveSink() throws Exception {
        testSink(CassandraSinkTester.createTester(false), false);
    }
    
    @Test(enabled = false)
    public void testHdfsSink() throws Exception {
        testSink(new HdfsSinkTester(), false);
    }
    
    @Test
    public void testJdbcSink() throws Exception {
        testSink(new JdbcSinkTester(), true);
    }

    @Test(enabled = false)
    public void testElasticSearchSink() throws Exception {
        testSink(new ElasticSearchSinkTester(), true);
    }
    
    private void testSink(SinkTester tester, boolean builtin) throws Exception {
        tester.startServiceContainer(pulsarCluster);
        try {
            runSinkTester(tester, builtin);
        } finally {
            tester.stopServiceContainer(pulsarCluster);
        }
    }


    private <ServiceContainerT extends GenericContainer>  void testSink(SinkTester<ServiceContainerT> sinkTester,
                                                                        boolean builtinSink,
                                                                        SourceTester<ServiceContainerT> sourceTester)
            throws Exception {
        ServiceContainerT serviceContainer = sinkTester.startServiceContainer(pulsarCluster);
        try {
            runSinkTester(sinkTester, builtinSink);
            if (null != sourceTester) {
                sourceTester.setServiceContainer(serviceContainer);
                testSource(sourceTester);
            }
        } finally {
            sinkTester.stopServiceContainer(pulsarCluster);
        }
    }
    private void runSinkTester(SinkTester tester, boolean builtin) throws Exception {
        final String tenant = TopicName.PUBLIC_TENANT;
        final String namespace = TopicName.DEFAULT_NAMESPACE;
        final String inputTopicName = "test-sink-connector-"
            + tester.getSinkType() + "-" + functionRuntimeType + "-input-topic-" + randomName(8);
        final String sinkName = "test-sink-connector-"
            + tester.getSinkType().name().toLowerCase() + "-" + functionRuntimeType + "-name-" + randomName(8);
        final int numMessages = 20;

        // prepare the testing environment for sink
        prepareSink(tester);

        ensureSubscriptionCreated(
            inputTopicName,
            String.format("public/default/%s", sinkName),
            tester.getInputTopicSchema());

        // submit the sink connector
        submitSinkConnector(tester, tenant, namespace, sinkName, inputTopicName);

        // get sink info
        getSinkInfoSuccess(tester, tenant, namespace, sinkName, builtin);

        // get sink status
        getSinkStatus(tenant, namespace, sinkName);

        // produce messages
        Map<String, String> kvs;
        if (tester instanceof JdbcSinkTester) {
            kvs = produceSchemaMessagesToInputTopic(inputTopicName, numMessages, AvroSchema.of(JdbcSinkTester.Foo.class));
        } else {
            kvs = produceMessagesToInputTopic(inputTopicName, numMessages);
        }

        // wait for sink to process messages
        waitForProcessingMessages(tenant, namespace, sinkName, numMessages);

        // validate the sink result
        tester.validateSinkResult(kvs);

        // delete the sink
        deleteSink(tenant, namespace, sinkName);

        // get sink info (sink should be deleted)
        getSinkInfoNotFound(tenant, namespace, sinkName);
    }

    protected void prepareSink(SinkTester tester) throws Exception {
        tester.prepareSink();
    }

    protected void submitSinkConnector(SinkTester tester,
                                       String tenant,
                                       String namespace,
                                       String sinkName,
                                       String inputTopicName) throws Exception {
        String[] commands;
        if (tester.getSinkType() != SinkTester.SinkType.UNDEFINED) {
            commands = new String[] {
                    PulsarCluster.ADMIN_SCRIPT,
                    "sink", "create",
                    "--tenant", tenant,
                    "--namespace", namespace,
                    "--name", sinkName,
                    "--sink-type", tester.sinkType().name().toLowerCase(),
                    "--sinkConfig", new Gson().toJson(tester.sinkConfig()),
                    "--inputs", inputTopicName
            };
        } else {
            commands = new String[] {
                    PulsarCluster.ADMIN_SCRIPT,
                    "sink", "create",
                    "--tenant", tenant,
                    "--namespace", namespace,
                    "--name", sinkName,
                    "--archive", tester.getSinkArchive(),
                    "--classname", tester.getSinkClassName(),
                    "--sinkConfig", new Gson().toJson(tester.sinkConfig()),
                    "--inputs", inputTopicName
            };
        }
        log.info("Run command : {}", StringUtils.join(commands, ' '));
        ContainerExecResult result = pulsarCluster.getAnyWorker().execCmd(commands);
        assertTrue(
            result.getStdout().contains("\"Created successfully\""),
            result.getStdout());
    }

    protected void getSinkInfoSuccess(SinkTester tester,
                                      String tenant,
                                      String namespace,
                                      String sinkName,
                                      boolean builtin) throws Exception {
        String[] commands = {
            PulsarCluster.ADMIN_SCRIPT,
            "functions",
            "get",
            "--tenant", tenant,
            "--namespace", namespace,
            "--name", sinkName
        };
        ContainerExecResult result = pulsarCluster.getAnyWorker().execCmd(commands);
        log.info("Get sink info : {}", result.getStdout());
        if (builtin) {
            assertTrue(
                    result.getStdout().contains("\"builtin\": \"" + tester.getSinkType().name().toLowerCase() + "\""),
                    result.getStdout()
            );
        } else {
            assertTrue(
                    result.getStdout().contains("\"className\": \"" + tester.getSinkClassName() + "\""),
                    result.getStdout()
            );
        }
    }

    protected void getSinkStatus(String tenant, String namespace, String sinkName) throws Exception {
        String[] commands = {
            PulsarCluster.ADMIN_SCRIPT,
            "functions",
            "getstatus",
            "--tenant", tenant,
            "--namespace", namespace,
            "--name", sinkName
        };
        while (true) {
            try {
                ContainerExecResult result = pulsarCluster.getAnyWorker().execCmd(commands);
                log.info("Get sink status : {}", result.getStdout());
                if (result.getStdout().contains("\"running\": true")) {
                    return;
                }
            } catch (ContainerExecException e) {
                // expected in early iterations
            }
            log.info("Backoff 1 second until the function is running");
            TimeUnit.SECONDS.sleep(1);
        }
    }

    protected Map<String, String> produceMessagesToInputTopic(String inputTopicName,
                                                              int numMessages) throws Exception {
        @Cleanup
        PulsarClient client = PulsarClient.builder()
            .serviceUrl(pulsarCluster.getPlainTextServiceUrl())
            .build();
        @Cleanup
        Producer<String> producer = client.newProducer(Schema.STRING)
            .topic(inputTopicName)
            .create();
        LinkedHashMap<String, String> kvs = new LinkedHashMap<>();
        for (int i = 0; i < numMessages; i++) {
            String key = "key-" + i;
            String value = "value-" + i;
            kvs.put(key, value);
            producer.newMessage()
                .key(key)
                .value(value)
                .send();
        }
        return kvs;
    }

    // This for JdbcSinkTester
    protected Map<String, String> produceSchemaMessagesToInputTopic(String inputTopicName,
                                                                    int numMessages,
                                                                    Schema<Foo> schema) throws Exception {
        @Cleanup
        PulsarClient client = PulsarClient.builder()
            .serviceUrl(pulsarCluster.getPlainTextServiceUrl())
            .build();
        @Cleanup
        Producer<Foo> producer = client.newProducer(schema)
            .topic(inputTopicName)
            .create();
        LinkedHashMap<String, String> kvs = new LinkedHashMap<>();
        for (int i = 0; i < numMessages; i++) {
            String key = "key-" + i;

            JdbcSinkTester.Foo obj = new JdbcSinkTester.Foo();
            obj.setField1("field1_" + i);
            obj.setField2("field2_" + i);
            obj.setField3(i);
            String value = new String(schema.encode(obj));

            kvs.put(key, value);
            producer.newMessage()
                .key(key)
                .value(obj)
                .send();
        }
        return kvs;
    }

    protected void waitForProcessingMessages(String tenant,
                                             String namespace,
                                             String sinkName,
                                             int numMessages) throws Exception {
        String[] commands = {
            PulsarCluster.ADMIN_SCRIPT,
            "functions",
            "getstatus",
            "--tenant", tenant,
            "--namespace", namespace,
            "--name", sinkName
        };
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (true) {
            try {
                ContainerExecResult result = pulsarCluster.getAnyWorker().execCmd(commands);
                log.info("Get sink status : {}", result.getStdout());
                if (result.getStdout().contains("\"numProcessed\": \"" + numMessages + "\"")) {
                    return;
                }
            } catch (ContainerExecException e) {
                // expected in early iterations
            }

            log.info("{} ms has elapsed but the sink {} hasn't process {} messages, backoff to wait for another 1 second",
                stopwatch.elapsed(TimeUnit.MILLISECONDS), sinkName, numMessages);
            TimeUnit.SECONDS.sleep(1);
        }
    }

    protected void deleteSink(String tenant, String namespace, String sinkName) throws Exception {
        String[] commands = {
            PulsarCluster.ADMIN_SCRIPT,
            "sink",
            "delete",
            "--tenant", tenant,
            "--namespace", namespace,
            "--name", sinkName
        };
        ContainerExecResult result = pulsarCluster.getAnyWorker().execCmd(commands);
        assertTrue(
            result.getStdout().contains("Deleted successfully"),
            result.getStdout()
        );
        assertTrue(
            result.getStderr().isEmpty(),
            result.getStderr()
        );
    }

    protected void getSinkInfoNotFound(String tenant, String namespace, String sinkName) throws Exception {
        String[] commands = {
            PulsarCluster.ADMIN_SCRIPT,
            "functions",
            "get",
            "--tenant", tenant,
            "--namespace", namespace,
            "--name", sinkName
        };
        try {
            pulsarCluster.getAnyWorker().execCmd(commands);
            fail("Command should have exited with non-zero");
        } catch (ContainerExecException e) {
            assertTrue(e.getResult().getStderr().contains("Reason: Function " + sinkName + " doesn't exist"));
        }
    }

    //
    // Source Test
    //

    private void testSource(SourceTester tester)  throws Exception {
        final String tenant = TopicName.PUBLIC_TENANT;
        final String namespace = TopicName.DEFAULT_NAMESPACE;
        final String outputTopicName = "test-source-connector-"
            + functionRuntimeType + "-output-topic-" + randomName(8);
        final String sourceName = "test-source-connector-"
            + functionRuntimeType + "-name-" + randomName(8);
        final int numMessages = 20;

        @Cleanup
        PulsarClient client = PulsarClient.builder()
            .serviceUrl(pulsarCluster.getPlainTextServiceUrl())
            .build();

        @Cleanup
        Consumer<String> consumer = client.newConsumer(Schema.STRING)
            .topic(outputTopicName)
            .subscriptionName("source-tester")
            .subscriptionType(SubscriptionType.Exclusive)
            .subscribe();

        // prepare the testing environment for source
        prepareSource(tester);

        // submit the source connector
        submitSourceConnector(tester, tenant, namespace, sourceName, outputTopicName);

        // get source info
        getSourceInfoSuccess(tester, tenant, namespace, sourceName);

        // get source status
        getSourceStatus(tenant, namespace, sourceName);

        // produce messages
        Map<String, String> kvs = tester.produceSourceMessages(numMessages);

        // wait for source to process messages
        waitForProcessingSourceMessages(tenant, namespace, sourceName, numMessages);

        // validate the source result
        validateSourceResult(consumer, kvs);

        // delete the source
        deleteSource(tenant, namespace, sourceName);

        // get source info (source should be deleted)
        getSourceInfoNotFound(tenant, namespace, sourceName);
    }

    protected void prepareSource(SourceTester tester) throws Exception {
        tester.prepareSource();
    }

    protected void submitSourceConnector(SourceTester tester,
                                         String tenant,
                                         String namespace,
                                         String sourceName,
                                         String outputTopicName) throws Exception {
        String[] commands = {
            PulsarCluster.ADMIN_SCRIPT,
            "source", "create",
            "--tenant", tenant,
            "--namespace", namespace,
            "--name", sourceName,
            "--source-type", tester.sourceType(),
            "--sourceConfig", new Gson().toJson(tester.sourceConfig()),
            "--destinationTopicName", outputTopicName
        };
        log.info("Run command : {}", StringUtils.join(commands, ' '));
        ContainerExecResult result = pulsarCluster.getAnyWorker().execCmd(commands);
        assertTrue(
            result.getStdout().contains("\"Created successfully\""),
            result.getStdout());
    }

    protected void getSourceInfoSuccess(SourceTester tester,
                                        String tenant,
                                        String namespace,
                                        String sourceName) throws Exception {
        String[] commands = {
            PulsarCluster.ADMIN_SCRIPT,
            "functions",
            "get",
            "--tenant", tenant,
            "--namespace", namespace,
            "--name", sourceName
        };
        ContainerExecResult result = pulsarCluster.getAnyWorker().execCmd(commands);
        log.info("Get source info : {}", result.getStdout());
        assertTrue(
            result.getStdout().contains("\"builtin\": \"" + tester.getSourceType() + "\""),
            result.getStdout()
        );
    }

    protected void getSourceStatus(String tenant, String namespace, String sourceName) throws Exception {
        String[] commands = {
            PulsarCluster.ADMIN_SCRIPT,
            "functions",
            "getstatus",
            "--tenant", tenant,
            "--namespace", namespace,
            "--name", sourceName
        };
        while (true) {
            try {
                ContainerExecResult result = pulsarCluster.getAnyWorker().execCmd(commands);
                log.info("Get source status : {}", result.getStdout());
                if (result.getStdout().contains("\"running\": true")) {
                    return;
                }
            } catch (ContainerExecException e) {
                // expected for early iterations
            }
            log.info("Backoff 1 second until the function is running");
            TimeUnit.SECONDS.sleep(1);
        }
    }

    protected void validateSourceResult(Consumer<String> consumer,
                                        Map<String, String> kvs) throws Exception {
        for (Map.Entry<String, String> kv : kvs.entrySet()) {
            Message<String> msg = consumer.receive();
            assertEquals(kv.getKey(), msg.getKey());
            assertEquals(kv.getValue(), msg.getValue());
        }
    }

    protected void waitForProcessingSourceMessages(String tenant,
                                                   String namespace,
                                                   String sourceName,
                                                   int numMessages) throws Exception {
        String[] commands = {
            PulsarCluster.ADMIN_SCRIPT,
            "functions",
            "getstatus",
            "--tenant", tenant,
            "--namespace", namespace,
            "--name", sourceName
        };
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (true) {
            try {
                ContainerExecResult result = pulsarCluster.getAnyWorker().execCmd(commands);
                log.info("Get source status : {}", result.getStdout());
                if (result.getStdout().contains("\"numProcessed\": \"" + numMessages + "\"")) {
                    return;
                }
            } catch (ContainerExecException e) {
                // expected for early iterations
            }
            log.info("{} ms has elapsed but the source hasn't process {} messages, backoff to wait for another 1 second",
                stopwatch.elapsed(TimeUnit.MILLISECONDS), numMessages);
            TimeUnit.SECONDS.sleep(1);
        }
    }

    protected void deleteSource(String tenant, String namespace, String sourceName) throws Exception {
        String[] commands = {
            PulsarCluster.ADMIN_SCRIPT,
            "source",
            "delete",
            "--tenant", tenant,
            "--namespace", namespace,
            "--name", sourceName
        };
        ContainerExecResult result = pulsarCluster.getAnyWorker().execCmd(commands);
        assertTrue(
            result.getStdout().contains("Delete source successfully"),
            result.getStdout()
        );
        assertTrue(
            result.getStderr().isEmpty(),
            result.getStderr()
        );
    }

    protected void getSourceInfoNotFound(String tenant, String namespace, String sourceName) throws Exception {
        String[] commands = {
            PulsarCluster.ADMIN_SCRIPT,
            "functions",
            "get",
            "--tenant", tenant,
            "--namespace", namespace,
            "--name", sourceName
        };
        try {
            pulsarCluster.getAnyWorker().execCmd(commands);
            fail("Command should have exited with non-zero");
        } catch (ContainerExecException e) {
            assertTrue(e.getResult().getStderr().contains("Reason: Function " + sourceName + " doesn't exist"));
        }
    }

    //
    // Test CRUD functions on different runtimes.
    //

    @Test(enabled = false)
    public void testPythonExclamationFunction() throws Exception {
        testExclamationFunction(Runtime.PYTHON);
    }

    @Test
    public void testJavaExclamationFunction() throws Exception {
        testExclamationFunction(Runtime.JAVA);
    }

    private void testExclamationFunction(Runtime runtime) throws Exception {
        if (functionRuntimeType == FunctionRuntimeType.THREAD && runtime == Runtime.PYTHON) {
            // python can only run on process mode
            return;
        }

        String inputTopicName = "test-exclamation-" + runtime + "-input-" + randomName(8);
        String outputTopicName = "test-exclamation-" + runtime + "-output-" + randomName(8);
        String functionName = "test-exclamation-fn-" + randomName(8);
        final int numMessages = 10;

        // submit the exclamation function
        submitExclamationFunction(
            runtime, inputTopicName, outputTopicName, functionName);

        // get function info
        getFunctionInfoSuccess(functionName);

        // publish and consume result
        publishAndConsumeMessages(inputTopicName, outputTopicName, numMessages);

        // get function status
        getFunctionStatus(functionName, numMessages);

        // delete function
        deleteFunction(functionName);

        // get function info
        getFunctionInfoNotFound(functionName);
    }

    private static void submitExclamationFunction(Runtime runtime,
                                                  String inputTopicName,
                                                  String outputTopicName,
                                                  String functionName) throws Exception {
        submitFunction(
            runtime,
            inputTopicName,
            outputTopicName,
            functionName,
            getExclamationClass(runtime),
            Schema.STRING);
    }

    private static <T> void submitFunction(Runtime runtime,
                                           String inputTopicName,
                                           String outputTopicName,
                                           String functionName,
                                           String functionClass,
                                           Schema<T> inputTopicSchema) throws Exception {
        CommandGenerator generator;
        generator = CommandGenerator.createDefaultGenerator(inputTopicName, functionClass);
        generator.setSinkTopic(outputTopicName);
        generator.setFunctionName(functionName);
        String command;
        if (Runtime.JAVA == runtime) {
            command = generator.generateCreateFunctionCommand();
        } else if (Runtime.PYTHON == runtime) {
            generator.setRuntime(runtime);
            command = generator.generateCreateFunctionCommand(EXCLAMATION_PYTHON_FILE);
        } else {
            throw new IllegalArgumentException("Unsupported runtime : " + runtime);
        }
        String[] commands = {
            "sh", "-c", command
        };
        ContainerExecResult result = pulsarCluster.getAnyWorker().execCmd(
            commands);
        assertTrue(result.getStdout().contains("\"Created successfully\""));

        ensureSubscriptionCreated(inputTopicName, String.format("public/default/%s", functionName), inputTopicSchema);
    }

    private static <T> void ensureSubscriptionCreated(String inputTopicName,
                                                      String subscriptionName,
                                                      Schema<T> inputTopicSchema)
            throws Exception {
        // ensure the function subscription exists before we start producing messages
        try (PulsarClient client = PulsarClient.builder()
            .serviceUrl(pulsarCluster.getPlainTextServiceUrl())
            .build()) {
            try (Consumer<T> ignored = client.newConsumer(inputTopicSchema)
                .topic(inputTopicName)
                .subscriptionType(SubscriptionType.Shared)
                .subscriptionName(subscriptionName)
                .subscribe()) {
            }
        }
    }

    private static void getFunctionInfoSuccess(String functionName) throws Exception {
        ContainerExecResult result = pulsarCluster.getAnyWorker().execCmd(
            PulsarCluster.ADMIN_SCRIPT,
            "functions",
            "get",
            "--tenant", "public",
            "--namespace", "default",
            "--name", functionName
        );
        assertTrue(result.getStdout().contains("\"name\": \"" + functionName + "\""));
    }

    private static void getFunctionInfoNotFound(String functionName) throws Exception {
        try {
            pulsarCluster.getAnyWorker().execCmd(
                    PulsarCluster.ADMIN_SCRIPT,
                    "functions",
                    "get",
                    "--tenant", "public",
                    "--namespace", "default",
                    "--name", functionName);
            fail("Command should have exited with non-zero");
        } catch (ContainerExecException e) {
            assertTrue(e.getResult().getStderr().contains("Reason: Function " + functionName + " doesn't exist"));
        }
    }

    private static void getFunctionStatus(String functionName, int numMessages) throws Exception {
        ContainerExecResult result = pulsarCluster.getAnyWorker().execCmd(
            PulsarCluster.ADMIN_SCRIPT,
            "functions",
            "getstatus",
            "--tenant", "public",
            "--namespace", "default",
            "--name", functionName
        );
        assertTrue(result.getStdout().contains("\"running\": true"));
        assertTrue(result.getStdout().contains("\"numProcessed\": \"" + numMessages + "\""));
        assertTrue(result.getStdout().contains("\"numSuccessfullyProcessed\": \"" + numMessages + "\""));
    }

    private static void publishAndConsumeMessages(String inputTopic,
                                                  String outputTopic,
                                                  int numMessages) throws Exception {
        @Cleanup PulsarClient client = PulsarClient.builder()
            .serviceUrl(pulsarCluster.getPlainTextServiceUrl())
            .build();
        @Cleanup Consumer<String> consumer = client.newConsumer(Schema.STRING)
            .topic(outputTopic)
            .subscriptionType(SubscriptionType.Exclusive)
            .subscriptionName("test-sub")
            .subscribe();
        @Cleanup Producer<String> producer = client.newProducer(Schema.STRING)
            .topic(inputTopic)
            .create();

        for (int i = 0; i < numMessages; i++) {
            producer.send("message-" + i);
        }

        for (int i = 0; i < numMessages; i++) {
            Message<String> msg = consumer.receive();
            assertEquals("message-" + i + "!", msg.getValue());
        }
    }

    private static void deleteFunction(String functionName) throws Exception {
        ContainerExecResult result = pulsarCluster.getAnyWorker().execCmd(
            PulsarCluster.ADMIN_SCRIPT,
            "functions",
            "delete",
            "--tenant", "public",
            "--namespace", "default",
            "--name", functionName
        );
        assertTrue(result.getStdout().contains("Deleted successfully"));
        assertTrue(result.getStderr().isEmpty());
    }

    @Test
    public void testAutoSchemaFunction() throws Exception {
        String inputTopicName = "test-autoschema-input-" + randomName(8);
        String outputTopicName = "test-autoshcema-output-" + randomName(8);
        String functionName = "test-autoschema-fn-" + randomName(8);
        final int numMessages = 10;

        // submit the exclamation function
        submitFunction(
            Runtime.JAVA, inputTopicName, outputTopicName, functionName,
            AutoSchemaFunction.class.getName(),
            Schema.AVRO(CustomObject.class));

        // get function info
        getFunctionInfoSuccess(functionName);

        // publish and consume result
        publishAndConsumeAvroMessages(inputTopicName, outputTopicName, numMessages);

        // get function status
        getFunctionStatus(functionName, numMessages);

        // delete function
        deleteFunction(functionName);

        // get function info
        getFunctionInfoNotFound(functionName);
    }

    private static void publishAndConsumeAvroMessages(String inputTopic,
                                                      String outputTopic,
                                                      int numMessages) throws Exception {
        @Cleanup PulsarClient client = PulsarClient.builder()
            .serviceUrl(pulsarCluster.getPlainTextServiceUrl())
            .build();
        @Cleanup Consumer<String> consumer = client.newConsumer(Schema.STRING)
            .topic(outputTopic)
            .subscriptionType(SubscriptionType.Exclusive)
            .subscriptionName("test-sub")
            .subscribe();
        @Cleanup Producer<CustomObject> producer = client.newProducer(Schema.AVRO(CustomObject.class))
            .topic(inputTopic)
            .create();

        for (int i = 0; i < numMessages; i++) {
            CustomObject co = new CustomObject(i);
            producer.send(co);
        }

        for (int i = 0; i < numMessages; i++) {
            Message<String> msg = consumer.receive();
            assertEquals("value-" + i, msg.getValue());
        }
    }

}