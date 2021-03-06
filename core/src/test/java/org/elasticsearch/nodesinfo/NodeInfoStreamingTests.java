/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.nodesinfo;

import com.google.common.collect.ImmutableMap;
import org.elasticsearch.Build;
import org.elasticsearch.Version;
import org.elasticsearch.action.admin.cluster.node.info.NodeInfo;
import org.elasticsearch.action.admin.cluster.node.info.PluginsAndModules;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.BoundTransportAddress;
import org.elasticsearch.common.transport.DummyTransportAddress;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.http.HttpInfo;
import org.elasticsearch.monitor.jvm.JvmInfo;
import org.elasticsearch.monitor.os.DummyOsInfo;
import org.elasticsearch.monitor.os.OsInfo;
import org.elasticsearch.monitor.process.ProcessInfo;
import org.elasticsearch.plugins.DummyPluginInfo;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.VersionUtils;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.threadpool.ThreadPoolInfo;
import org.elasticsearch.transport.TransportInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.hamcrest.core.IsEqual.equalTo;

/**
 *
 */
public class NodeInfoStreamingTests extends ESTestCase {

    @AwaitsFix(bugUrl = "need to figure out how to test streaming to earlier versions")
    public void testNodeInfoStreaming() throws IOException {
        NodeInfo nodeInfo = createNodeInfo();

        // test version on or after V_2_2_0
        Version version = VersionUtils.randomVersionBetween(random(), Version.V_2_2_0, Version.CURRENT);
        BytesStreamOutput out = new BytesStreamOutput();
        out.setVersion(version);
        nodeInfo.writeTo(out);
        out.close();
        StreamInput in = StreamInput.wrap(out.bytes());
        in.setVersion(version);
        NodeInfo readNodeInfo = NodeInfo.readNodeInfo(in);
        assertExpectedUnchanged(nodeInfo, readNodeInfo);

        comparePluginsAndModulesOnOrAfter2_2_0(nodeInfo, readNodeInfo);
        compareOSOnOrAfter2_2_0(nodeInfo, readNodeInfo);

        // test version before V_2_2_0
        version = VersionUtils.randomVersionBetween(random(), Version.V_2_0_0, Version.V_2_1_1);
        out = new BytesStreamOutput();
        out.setVersion(version);
        nodeInfo.writeTo(out);
        out.close();
        in = StreamInput.wrap(out.bytes());
        in.setVersion(version);
        readNodeInfo = NodeInfo.readNodeInfo(in);
        assertExpectedUnchanged(nodeInfo, readNodeInfo);

        comparePluginsAndModulesBefore2_2_0(nodeInfo, readNodeInfo);
        compareOSOnOrBefore2_2_0(nodeInfo, readNodeInfo);
    }

    // checks all properties that are expected to be unchanged. Once we start changing them between versions this method has to be changed as well
    private void assertExpectedUnchanged(NodeInfo nodeInfo, NodeInfo readNodeInfo) throws IOException {
        assertThat(nodeInfo.getBuild().toString(), equalTo(readNodeInfo.getBuild().toString()));
        assertThat(nodeInfo.getHostname(), equalTo(readNodeInfo.getHostname()));
        assertThat(nodeInfo.getVersion(), equalTo(readNodeInfo.getVersion()));
        assertThat(nodeInfo.getServiceAttributes().size(), equalTo(readNodeInfo.getServiceAttributes().size()));
        for (Map.Entry<String, String> entry : nodeInfo.getServiceAttributes().entrySet()) {
            assertNotNull(readNodeInfo.getServiceAttributes().get(entry.getKey()));
            assertThat(readNodeInfo.getServiceAttributes().get(entry.getKey()), equalTo(entry.getValue()));
        }
        compareJsonOutput(nodeInfo.getHttp(), readNodeInfo.getHttp());
        compareJsonOutput(nodeInfo.getJvm(), readNodeInfo.getJvm());
        compareJsonOutput(nodeInfo.getProcess(), readNodeInfo.getProcess());
        compareJsonOutput(nodeInfo.getSettings(), readNodeInfo.getSettings());
        compareJsonOutput(nodeInfo.getThreadPool(), readNodeInfo.getThreadPool());
        compareJsonOutput(nodeInfo.getTransport(), readNodeInfo.getTransport());
        compareJsonOutput(nodeInfo.getNode(), readNodeInfo.getNode());
    }

    private void comparePluginsAndModulesBefore2_2_0(NodeInfo nodeInfo, NodeInfo readNodeInfo) throws IOException {
        assertThat(readNodeInfo.getPlugins().getPluginInfos().size(), equalTo(nodeInfo.getPlugins().getModuleInfos().size() + nodeInfo.getPlugins().getPluginInfos().size()));
        for (int i = 0; i < nodeInfo.getPlugins().getPluginInfos().size(); i++) {
            compareJsonOutput(readNodeInfo.getPlugins().getPluginInfos().get(i), nodeInfo.getPlugins().getPluginInfos().get(i));
        }
        for (int i = 0; i < nodeInfo.getPlugins().getPluginInfos().size(); i++) {
            compareJsonOutput(readNodeInfo.getPlugins().getPluginInfos().get(i + nodeInfo.getPlugins().getPluginInfos().size()), nodeInfo.getPlugins().getModuleInfos().get(i));
        }
    }

    private void comparePluginsAndModulesOnOrAfter2_2_0(NodeInfo nodeInfo, NodeInfo readNodeInfo) throws IOException {
        ToXContent.Params params = ToXContent.EMPTY_PARAMS;
        XContentBuilder pluginsAndModules = jsonBuilder();
        pluginsAndModules.startObject();
        nodeInfo.getPlugins().toXContent(pluginsAndModules, params);
        pluginsAndModules.endObject();
        XContentBuilder readPluginsAndModules = jsonBuilder();
        readPluginsAndModules.startObject();
        readNodeInfo.getPlugins().toXContent(readPluginsAndModules, params);
        readPluginsAndModules.endObject();
        assertThat(pluginsAndModules.string(), equalTo(readPluginsAndModules.string()));
    }

    private void compareJsonOutput(ToXContent param1, ToXContent param2) throws IOException {
        assertThat(Strings.toString(param1, true), equalTo(Strings.toString(param2, true)));
    }

    // see https://github.com/elastic/elasticsearch/issues/15422
    private void compareOSOnOrBefore2_2_0(NodeInfo nodeInfo, NodeInfo readNodeInfo) {
        OsInfo osInfo = nodeInfo.getOs();
        OsInfo readOsInfo = readNodeInfo.getOs();
        assertThat(osInfo.getAllocatedProcessors(), equalTo(readOsInfo.getAllocatedProcessors()));
        assertThat(osInfo.getAvailableProcessors(), equalTo(readOsInfo.getAvailableProcessors()));
        assertThat(osInfo.getRefreshInterval(), equalTo(readOsInfo.getRefreshInterval()));
    }

    private void compareOSOnOrAfter2_2_0(NodeInfo nodeInfo, NodeInfo readNodeInfo) throws IOException {
        compareJsonOutput(nodeInfo.getOs(), readNodeInfo.getOs());
    }

    private NodeInfo createNodeInfo() {
        Build build = Build.CURRENT;
        DiscoveryNode node = new DiscoveryNode("test_node", DummyTransportAddress.INSTANCE, VersionUtils.randomVersion(random()));
        ImmutableMap<String, String> serviceAttributes = ImmutableMap.of("test", "attribute");
        Settings settings = Settings.builder().put("test", "setting").build();
        OsInfo osInfo = DummyOsInfo.INSTANCE;
        ProcessInfo process = new ProcessInfo(randomInt(), randomBoolean());
        JvmInfo jvm = JvmInfo.jvmInfo();
        List<ThreadPool.Info> threadPoolInfos = new ArrayList<>();
        threadPoolInfos.add(new ThreadPool.Info("test_threadpool", ThreadPool.ThreadPoolType.FIXED, 5));
        ThreadPoolInfo threadPoolInfo = new ThreadPoolInfo(threadPoolInfos);
        Map<String, BoundTransportAddress> profileAddresses = new HashMap<>();
        BoundTransportAddress dummyBoundTransportAddress = new BoundTransportAddress(new TransportAddress[]{DummyTransportAddress.INSTANCE}, DummyTransportAddress.INSTANCE);
        profileAddresses.put("test_address", dummyBoundTransportAddress);
        TransportInfo transport = new TransportInfo(dummyBoundTransportAddress, profileAddresses);
        HttpInfo htttpInfo = new HttpInfo(dummyBoundTransportAddress, randomLong());
        PluginsAndModules plugins = new PluginsAndModules();
        plugins.addModule(DummyPluginInfo.INSTANCE);
        plugins.addPlugin(DummyPluginInfo.INSTANCE);
        return new NodeInfo(VersionUtils.randomVersion(random()), build, node, serviceAttributes, settings, osInfo, process, jvm, threadPoolInfo, transport, htttpInfo, plugins);
    }
}
