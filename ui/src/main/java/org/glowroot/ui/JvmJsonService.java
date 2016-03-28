/*
 * Copyright 2013-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.ui;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nullable;
import javax.management.ObjectName;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.live.LiveJvmService;
import org.glowroot.common.live.LiveJvmService.AgentNotConnectedException;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.UsedByJsonSerialization;
import org.glowroot.storage.repo.AgentRepository;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.HostInfo;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.JavaInfo;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.ProcessInfo;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.SystemInfo;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.Availability;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.Capabilities;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapDumpFileInfo;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDump;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDumpRequest.MBeanDumpKind;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ThreadDump;

import static com.google.common.base.Preconditions.checkNotNull;

@JsonService
class JvmJsonService {

    private static final Logger logger = LoggerFactory.getLogger(JvmJsonService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final AgentRepository agentRepository;
    private final @Nullable LiveJvmService liveJvmService;

    JvmJsonService(AgentRepository agentRepository, @Nullable LiveJvmService liveJvmService) {
        this.agentRepository = agentRepository;
        this.liveJvmService = liveJvmService;
    }

    @GET("/backend/jvm/agent-connected")
    String getAgentConnected(String queryString) throws Exception {
        checkNotNull(liveJvmService);
        String agentId = getAgentId(queryString);
        return Boolean.toString(liveJvmService.isAvailable(agentId));
    }

    @GET("/backend/jvm/system-info")
    String getSystemInfo(String queryString) throws Exception {
        String agentId = getAgentId(queryString);
        SystemInfo systemInfo = agentRepository.readSystemInfo(agentId);
        if (systemInfo == null) {
            return "{}";
        }
        HostInfo hostInfo = systemInfo.getHostInfo();
        ProcessInfo processInfo = systemInfo.getProcessInfo();
        JavaInfo javaInfo = systemInfo.getJavaInfo();

        StringWriter sw = new StringWriter();
        JsonGenerator jg = mapper.getFactory().createGenerator(sw);
        jg.writeStartObject();
        jg.writeObjectFieldStart("host");
        jg.writeStringField("hostName", hostInfo.getHostName());
        jg.writeNumberField("availableProcessors", hostInfo.getAvailableProcessors());
        if (hostInfo.hasTotalPhysicalMemoryBytes()) {
            jg.writeNumberField("totalPhysicalMemoryBytes",
                    hostInfo.getTotalPhysicalMemoryBytes().getValue());
        }
        jg.writeStringField("osName", hostInfo.getOsName());
        jg.writeStringField("osVersion", hostInfo.getOsVersion());
        jg.writeEndObject();
        jg.writeObjectFieldStart("process");
        if (processInfo.hasProcessId()) {
            jg.writeNumberField("processId", processInfo.getProcessId().getValue());
        }
        jg.writeNumberField("startTime", processInfo.getStartTime());
        jg.writeEndObject();
        jg.writeObjectFieldStart("java");
        jg.writeStringField("version", javaInfo.getVersion());
        jg.writeStringField("vm", javaInfo.getVm());
        jg.writeArrayFieldStart("args");
        for (String arg : javaInfo.getArgList()) {
            jg.writeString(arg);
        }
        jg.writeEndArray();
        jg.writeStringField("glowrootAgentVersion", javaInfo.getGlowrootAgentVersion());
        jg.writeEndObject();
        jg.writeEndObject();
        jg.close();
        return sw.toString();
    }

    @GET("/backend/jvm/thread-dump")
    String getThreadDump(String queryString) throws Exception {
        checkNotNull(liveJvmService);
        String agentId = getAgentId(queryString);
        ThreadDump threadDump;
        try {
            threadDump = liveJvmService.getThreadDump(agentId);
        } catch (AgentNotConnectedException e) {
            logger.debug(e.getMessage(), e);
            return "{\"agentNotConnected\":true}";
        }
        StringWriter sw = new StringWriter();
        JsonGenerator jg = mapper.getFactory().createGenerator(sw);
        jg.writeStartObject();
        jg.writeArrayFieldStart("transactions");
        for (ThreadDump.Transaction transaction : threadDump.getTransactionList()) {
            writeTransactionThread(transaction, jg);
        }
        jg.writeEndArray();
        jg.writeArrayFieldStart("unmatchedThreads");
        for (ThreadDump.Thread thread : threadDump.getUnmatchedThreadList()) {
            writeThread(thread, jg);
        }
        jg.writeEndArray();
        jg.writeFieldName("threadDumpingThread");
        writeThread(threadDump.getThreadDumpingThread(), jg);
        jg.writeEndObject();
        jg.close();
        return sw.toString();
    }

    @GET("/backend/jvm/heap-dump-default-dir")
    String getHeapDumpDefaultDir(String queryString) throws Exception {
        checkNotNull(liveJvmService);
        String agentId = getAgentId(queryString);
        if (!liveJvmService.isAvailable(agentId)) {
            return "{\"agentNotConnected\":true}";
        }
        SystemInfo systemInfo = agentRepository.readSystemInfo(agentId);
        checkNotNull(systemInfo);
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeStringField("directory", systemInfo.getJavaInfo().getHeapDumpDefaultDir());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @POST("/backend/jvm/available-disk-space")
    String getAvailableDiskSpace(String content) throws Exception {
        checkNotNull(liveJvmService);
        HeapDumpRequest request = mapper.readValue(content, ImmutableHeapDumpRequest.class);
        try {
            return Long.toString(
                    liveJvmService.getAvailableDiskSpace(request.agentId(), request.directory()));
        } catch (IOException e) {
            logger.debug(e.getMessage(), e);
            // this is for specific common errors, e.g. "Directory doesn't exist"
            StringBuilder sb = new StringBuilder();
            JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
            jg.writeStartObject();
            jg.writeStringField("error", e.getMessage());
            jg.writeEndObject();
            jg.close();
            return sb.toString();
        }
    }

    @POST("/backend/jvm/heap-dump")
    String heapDump(String content) throws Exception {
        checkNotNull(liveJvmService);
        HeapDumpRequest request = mapper.readValue(content, ImmutableHeapDumpRequest.class);
        HeapDumpFileInfo heapDumpFileInfo;
        try {
            heapDumpFileInfo = liveJvmService.heapDump(request.agentId(), request.directory());
        } catch (IOException e) {
            logger.debug(e.getMessage(), e);
            // this is for specific common errors, e.g. "Directory doesn't exist"
            StringBuilder sb = new StringBuilder();
            JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
            jg.writeStartObject();
            jg.writeStringField("error", e.getMessage());
            jg.writeEndObject();
            jg.close();
            return sb.toString();
        }
        StringWriter sw = new StringWriter();
        JsonGenerator jg = mapper.getFactory().createGenerator(sw);
        jg.writeStartObject();
        jg.writeStringField("filePath", heapDumpFileInfo.getFilePath());
        jg.writeNumberField("fileSizeBytes", heapDumpFileInfo.getFileSizeBytes());
        jg.writeEndObject();
        jg.close();
        return sw.toString();
    }

    @POST("/backend/jvm/gc")
    void performGC(String content) throws Exception {
        checkNotNull(liveJvmService);
        GcRequest request = mapper.readValue(content, ImmutableGcRequest.class);
        liveJvmService.gc(request.agentId());
    }

    @GET("/backend/jvm/mbean-tree")
    String getMBeanTree(String queryString) throws Exception {
        checkNotNull(liveJvmService);
        MBeanTreeRequest request = QueryStrings.decode(queryString, MBeanTreeRequest.class);
        MBeanDump mbeanDump;
        try {
            mbeanDump = liveJvmService.getMBeanDump(request.agentId(),
                    MBeanDumpKind.ALL_MBEANS_INCLUDE_ATTRIBUTES_FOR_SOME, request.expanded());
        } catch (AgentNotConnectedException e) {
            logger.debug(e.getMessage(), e);
            return "{\"agentNotConnected\":true}";
        }
        // can't use Maps.newTreeMap() because of OpenJDK6 type inference bug
        // see https://code.google.com/p/guava-libraries/issues/detail?id=635
        Map<String, MBeanTreeInnerNode> sortedRootNodes =
                new TreeMap<String, MBeanTreeInnerNode>(String.CASE_INSENSITIVE_ORDER);
        for (MBeanDump.MBeanInfo mbeanInfo : mbeanDump.getMbeanInfoList()) {
            ObjectName objectName = ObjectName.getInstance(mbeanInfo.getObjectName());
            String domain = objectName.getDomain();
            MBeanTreeInnerNode node = sortedRootNodes.get(domain);
            if (node == null) {
                node = new MBeanTreeInnerNode(domain);
                sortedRootNodes.put(domain, node);
            }
            List<String> propertyValues = ObjectNames.getPropertyValues(objectName);
            for (int i = 0; i < propertyValues.size() - 1; i++) {
                node = node.getOrCreateNode(propertyValues.get(i));
            }
            String name = objectName.toString();
            String value = propertyValues.get(propertyValues.size() - 1);
            if (request.expanded().contains(name)) {
                node.addLeafNode(new MBeanTreeLeafNode(value, name, true,
                        getSortedAttributeMap(mbeanInfo.getAttributeList())));
            } else {
                node.addLeafNode(new MBeanTreeLeafNode(value, name, false, null));
            }
        }
        return mapper.writeValueAsString(sortedRootNodes);
    }

    @GET("/backend/jvm/mbean-attribute-map")
    String getMBeanAttributeMap(String queryString) throws Exception {
        checkNotNull(liveJvmService);
        MBeanAttributeMapRequest request =
                QueryStrings.decode(queryString, MBeanAttributeMapRequest.class);
        MBeanDump mbeanDump = liveJvmService.getMBeanDump(request.agentId(),
                MBeanDumpKind.SOME_MBEANS_INCLUDE_ATTRIBUTES,
                ImmutableList.of(request.objectName()));
        List<MBeanDump.MBeanInfo> mbeanInfos = mbeanDump.getMbeanInfoList();
        if (mbeanInfos.isEmpty()) {
            throw new IllegalStateException(
                    "Could not find mbean with object name: " + request.objectName());
        }
        if (mbeanInfos.size() > 1) {
            logger.warn("returned more than one mbean with object name: {}", request.objectName());
        }
        MBeanDump.MBeanInfo mbeanInfo = mbeanInfos.get(0);
        return mapper.writeValueAsString(getSortedAttributeMap(mbeanInfo.getAttributeList()));
    }

    @GET("/backend/jvm/capabilities")
    String getCapabilities(String queryString) throws Exception {
        checkNotNull(liveJvmService);
        String agentId = getAgentId(queryString);
        Capabilities capabilities;
        try {
            capabilities = liveJvmService.getCapabilities(agentId);
        } catch (AgentNotConnectedException e) {
            logger.debug(e.getMessage(), e);
            return "{\"agentNotConnected\":true}";
        }
        StringWriter sw = new StringWriter();
        JsonGenerator jg = mapper.getFactory().createGenerator(sw);
        jg.writeStartObject();
        writeAvailability("threadCpuTime", capabilities.getThreadCpuTime(), jg);
        writeAvailability("threadContentionTime", capabilities.getThreadContentionTime(), jg);
        writeAvailability("threadAllocatedBytes", capabilities.getThreadAllocatedBytes(), jg);
        jg.writeEndObject();
        jg.close();
        return sw.toString();
    }

    private void writeAvailability(String fieldName, Availability availability, JsonGenerator jg)
            throws IOException {
        jg.writeObjectFieldStart(fieldName);
        jg.writeBooleanField("available", availability.getAvailable());
        jg.writeStringField("reason", availability.getReason());
        jg.writeEndObject();
    }

    private static String getAgentId(String queryString) {
        return QueryStringDecoder.decodeComponent(queryString.substring("agent-id".length() + 1));
    }

    private static void writeTransactionThread(ThreadDump.Transaction transaction, JsonGenerator jg)
            throws IOException {
        jg.writeStartObject();
        jg.writeStringField("traceId", transaction.getTraceId());
        jg.writeStringField("transactionType", transaction.getTransactionType());
        jg.writeStringField("transactionName", transaction.getTransactionName());
        jg.writeNumberField("totalDurationNanos", transaction.getTotalDurationNanos());
        jg.writeArrayFieldStart("threads");
        for (ThreadDump.Thread thread : transaction.getThreadList()) {
            writeThread(thread, jg);
        }
        jg.writeEndArray();
        jg.writeEndObject();
    }

    private static void writeThread(ThreadDump.Thread thread, JsonGenerator jg) throws IOException {
        jg.writeStartObject();
        jg.writeStringField("name", thread.getName());
        jg.writeStringField("state", thread.getState());
        jg.writeStringField("lockName", thread.getLockName());
        jg.writeArrayFieldStart("stackTraceElements");
        for (ThreadDump.StackTraceElement stackTraceElement : thread.getStackTraceElementList()) {
            writeStackTraceElement(stackTraceElement, jg);
        }
        jg.writeEndArray();
        jg.writeEndObject();
    }

    private static void writeStackTraceElement(ThreadDump.StackTraceElement stackTraceElement,
            JsonGenerator jg) throws IOException {
        jg.writeString(new StackTraceElement(stackTraceElement.getClassName(),
                stackTraceElement.getMethodName(), stackTraceElement.getFileName(),
                stackTraceElement.getLineNumber()).toString());
    }

    private static Map<String, /*@Nullable*/ Object> getSortedAttributeMap(
            List<MBeanDump.MBeanAttribute> attributes) {
        // can't use Maps.newTreeMap() because of OpenJDK6 type inference bug
        // see https://code.google.com/p/guava-libraries/issues/detail?id=635
        Map<String, /*@Nullable*/ Object> sortedAttributeMap =
                new TreeMap<String, /*@Nullable*/ Object>(String.CASE_INSENSITIVE_ORDER);
        for (MBeanDump.MBeanAttribute attribute : attributes) {
            sortedAttributeMap.put(attribute.getName(), getAttributeValue(attribute.getValue()));
        }
        return sortedAttributeMap;
    }

    private static @Nullable Object getAttributeValue(MBeanDump.MBeanValue value) {
        if (value.getNull()) {
            return null;
        }
        switch (value.getValCase()) {
            case STRING:
                return value.getString();
            case DOUBLE:
                return value.getDouble();
            case LONG:
                return value.getLong();
            case BOOLEAN:
                return value.getBoolean();
            case LIST:
                List</*@Nullable*/ Object> list = Lists.newArrayList();
                for (MBeanDump.MBeanValue val : value.getList().getValueList()) {
                    list.add(getAttributeValue(val));
                }
                return list;
            case MAP:
                Map<String, /*@Nullable*/ Object> map = Maps.newHashMap();
                for (MBeanDump.MBeanValueMapEntry val : value.getMap().getEntryList()) {
                    map.put(val.getKey(), getAttributeValue(val.getValue()));
                }
                return map;
            default:
                throw new IllegalStateException(
                        "Unexpected mbean value case: " + value.getValCase());
        }
    }

    @Value.Immutable
    interface HeapDumpRequest {
        String agentId();
        String directory();
    }

    @Value.Immutable
    interface GcRequest {
        String agentId();
    }

    @Value.Immutable
    interface MBeanTreeRequest {
        String agentId();
        List<String> expanded();
    }

    @Value.Immutable
    interface MBeanAttributeMapRequest {
        String agentId();
        String objectName();
    }

    interface MBeanTreeNode {
        String getNodeName();
    }

    static class MBeanTreeInnerNode implements MBeanTreeNode {

        private static final Ordering<MBeanTreeNode> ordering = new Ordering<MBeanTreeNode>() {
            @Override
            public int compare(MBeanTreeNode left, MBeanTreeNode right) {
                return left.getNodeName().compareToIgnoreCase(right.getNodeName());
            }
        };

        private final String name;

        // not using Map here since its possible for multiple leafs with same name
        // e.g. d:type=Foo,name=Bar and d:type=Foo,nonsense=Bar
        // both translate to a leaf named Bar under d/Foo
        private final List<MBeanTreeNode> childNodes = Lists.newArrayList();

        private final Map<String, MBeanTreeInnerNode> innerNodes = Maps.newHashMap();

        public MBeanTreeInnerNode(String name) {
            this.name = name;
        }

        @Override
        @UsedByJsonSerialization
        public String getNodeName() {
            return name;
        }

        @UsedByJsonSerialization
        public List<MBeanTreeNode> getChildNodes() {
            return ordering.sortedCopy(childNodes);
        }

        public MBeanTreeInnerNode getOrCreateNode(String name) {
            MBeanTreeInnerNode innerNode = innerNodes.get(name);
            if (innerNode == null) {
                innerNode = new MBeanTreeInnerNode(name);
                innerNodes.put(name, innerNode);
                childNodes.add(innerNode);
            }
            return innerNode;
        }

        public void addLeafNode(MBeanTreeLeafNode leafNode) {
            childNodes.add(leafNode);
        }
    }

    static class MBeanTreeLeafNode implements MBeanTreeNode {

        // nodeName may not be unique
        private final String nodeName;
        private final String objectName;
        private final boolean expanded;
        private final @Nullable Map<String, /*@Nullable*/ Object> attributeMap;

        public MBeanTreeLeafNode(String nodeName, String objectName, boolean expanded,
                @Nullable Map<String, /*@Nullable*/ Object> attributeMap) {
            this.nodeName = nodeName;
            this.objectName = objectName;
            this.expanded = expanded;
            this.attributeMap = attributeMap;
        }

        @Override
        @UsedByJsonSerialization
        public String getNodeName() {
            return nodeName;
        }

        @UsedByJsonSerialization
        public String getObjectName() {
            return objectName;
        }

        @UsedByJsonSerialization
        public boolean isExpanded() {
            return expanded;
        }

        @UsedByJsonSerialization
        public @Nullable Map<String, /*@Nullable*/ Object> getAttributeMap() {
            return attributeMap;
        }
    }
}
