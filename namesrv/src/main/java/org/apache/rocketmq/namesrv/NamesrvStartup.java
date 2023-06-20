/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.namesrv;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;
import org.apache.rocketmq.common.MQVersion;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.namesrv.NamesrvConfig;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.remoting.netty.NettyServerConfig;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.apache.rocketmq.srvutil.ServerUtil;
import org.apache.rocketmq.srvutil.ShutdownHookThread;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.Callable;

public class NamesrvStartup {

    private static InternalLogger log;
    private static Properties properties = null;
    private static CommandLine commandLine = null;

    public static void main(String[] args) {
        main0(args);
    }

    public static NamesrvController main0(String[] args) {

        try {
            // 根据参数创建 nameSrvController
            NamesrvController controller = createNamesrvController(args);
            // 启动 nameSrvController
            start(controller);
            //打印日志
            String tip = "The Name Server boot success. serializeType=" + RemotingCommand.getSerializeTypeConfigInThisServer();
            log.info(tip);
            System.out.printf("%s%n", tip);
            return controller;
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(-1);
        }

        return null;
    }

    /**
     * 创建namesrv控制器
     *
     * @param args arg游戏
     * @return {@link NamesrvController}
     * @throws IOException    ioexception
     * @throws JoranException joran例外
     */
    public static NamesrvController createNamesrvController(String[] args) throws IOException, JoranException {
        // 设置版本
        System.setProperty(RemotingCommand.REMOTING_VERSION_KEY, Integer.toString(MQVersion.CURRENT_VERSION));
        // 创建命令行选项
        Options options = ServerUtil.buildCommandlineOptions(new Options());
        // 解析命令行
        commandLine = ServerUtil.parseCmdLine("mqnamesrv", args, buildCommandlineOptions(options), new PosixParser());
        // 没有命令行则退出
        if (null == commandLine) {
            System.exit(-1);
            return null;
        }
        // NameSrv 的配置
        final NamesrvConfig namesrvConfig = new NamesrvConfig();
        // NettyServer 的配置
        final NettyServerConfig nettyServerConfig = new NettyServerConfig();
        // 设置监听端口
        nettyServerConfig.setListenPort(9876);
        // 如果c 命令 Name server config properties file nameSrv的配置文件
        if (commandLine.hasOption('c')) {
            // 获取配置文件路径
            String file = commandLine.getOptionValue('c');
            // 如果配置文件不为空
            if (file != null) {
                InputStream in = new BufferedInputStream(new FileInputStream(file));
                // 配置文件加载为 properties
                properties = new Properties();
                properties.load(in);
                // 将配置文件中的配置加载到 namesrvConfig 和 nettyServerConfig 中
                MixAll.properties2Object(properties, namesrvConfig);
                MixAll.properties2Object(properties, nettyServerConfig);
                // 设置配置文件路径
                namesrvConfig.setConfigStorePath(file);
                // 打印日志
                System.out.printf("load config properties file OK, %s%n", file);
                in.close();
            }
        }
        // 如果命令行中有p 则打印配置
        if (commandLine.hasOption('p')) {
            // 加载 console 日志
            InternalLogger console = InternalLoggerFactory.getLogger(LoggerName.NAMESRV_CONSOLE_NAME);
            // 打印配置
            MixAll.printObjectProperties(console, namesrvConfig);
            MixAll.printObjectProperties(console, nettyServerConfig);
            // 退出
            System.exit(0);
        }
        // 命令行填充到 nameSrvConfig
        MixAll.properties2Object(ServerUtil.commandLine2Properties(commandLine), namesrvConfig);
        // 检查是否设置了 rocketMQ 的 Home
        if (null == namesrvConfig.getRocketmqHome()) {
            System.out.printf("Please set the %s variable in your environment to match the location of the RocketMQ installation%n", MixAll.ROCKETMQ_HOME_ENV);
            System.exit(-2);
        }
        // 设置 log
        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(lc);
        lc.reset();
        configurator.doConfigure(namesrvConfig.getRocketmqHome() + "/conf/logback_namesrv.xml");

        log = InternalLoggerFactory.getLogger(LoggerName.NAMESRV_LOGGER_NAME);
        // 打印配置
        MixAll.printObjectProperties(log, namesrvConfig);
        MixAll.printObjectProperties(log, nettyServerConfig);
        // 创建 nameSrvController
        final NamesrvController controller = new NamesrvController(namesrvConfig, nettyServerConfig);
        // 注册配置文件
        // remember all configs to prevent discard
        controller.getConfiguration().registerConfig(properties);
        // 返回 controller
        return controller;
    }

    public static NamesrvController start(final NamesrvController controller) throws Exception {
        // 如果 controller 为空则抛出异常
        if (null == controller) {
            throw new IllegalArgumentException("NamesrvController is null");
        }
        // controller 初始化
        boolean initResult = controller.initialize();
        // 初始化失败则退出
        if (!initResult) {
            controller.shutdown();
            System.exit(-3);
        }
        // 停止钩子
        Runtime.getRuntime().addShutdownHook(new ShutdownHookThread(log, (Callable<Void>) () -> {
            controller.shutdown();
            return null;
        }));

        controller.start();

        return controller;
    }

    public static void shutdown(final NamesrvController controller) {
        controller.shutdown();
    }

    public static Options buildCommandlineOptions(final Options options) {
        Option opt = new Option("c", "configFile", true, "Name server config properties file");
        opt.setRequired(false);
        options.addOption(opt);

        opt = new Option("p", "printConfigItem", false, "Print all config items");
        opt.setRequired(false);
        options.addOption(opt);

        return options;
    }

    public static Properties getProperties() {
        return properties;
    }
}
