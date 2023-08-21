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
package org.apache.rocketmq.broker;

import org.apache.rocketmq.acl.AccessValidator;
import org.apache.rocketmq.broker.client.*;
import org.apache.rocketmq.broker.client.net.Broker2Client;
import org.apache.rocketmq.broker.client.rebalance.RebalanceLockManager;
import org.apache.rocketmq.broker.dledger.DLedgerRoleChangeHandler;
import org.apache.rocketmq.broker.filter.CommitLogDispatcherCalcBitMap;
import org.apache.rocketmq.broker.filter.ConsumerFilterManager;
import org.apache.rocketmq.broker.filtersrv.FilterServerManager;
import org.apache.rocketmq.broker.latency.BrokerFastFailure;
import org.apache.rocketmq.broker.latency.BrokerFixedThreadPoolExecutor;
import org.apache.rocketmq.broker.longpolling.LmqPullRequestHoldService;
import org.apache.rocketmq.broker.longpolling.NotifyMessageArrivingListener;
import org.apache.rocketmq.broker.longpolling.PullRequestHoldService;
import org.apache.rocketmq.broker.mqtrace.ConsumeMessageHook;
import org.apache.rocketmq.broker.mqtrace.SendMessageHook;
import org.apache.rocketmq.broker.offset.ConsumerOffsetManager;
import org.apache.rocketmq.broker.offset.LmqConsumerOffsetManager;
import org.apache.rocketmq.broker.out.BrokerOuterAPI;
import org.apache.rocketmq.broker.plugin.MessageStoreFactory;
import org.apache.rocketmq.broker.plugin.MessageStorePluginContext;
import org.apache.rocketmq.broker.processor.*;
import org.apache.rocketmq.broker.slave.SlaveSynchronize;
import org.apache.rocketmq.broker.subscription.LmqSubscriptionGroupManager;
import org.apache.rocketmq.broker.subscription.SubscriptionGroupManager;
import org.apache.rocketmq.broker.topic.LmqTopicConfigManager;
import org.apache.rocketmq.broker.topic.TopicConfigManager;
import org.apache.rocketmq.broker.transaction.AbstractTransactionalMessageCheckListener;
import org.apache.rocketmq.broker.transaction.TransactionalMessageCheckService;
import org.apache.rocketmq.broker.transaction.TransactionalMessageService;
import org.apache.rocketmq.broker.transaction.queue.DefaultTransactionalMessageCheckListener;
import org.apache.rocketmq.broker.transaction.queue.TransactionalMessageBridge;
import org.apache.rocketmq.broker.transaction.queue.TransactionalMessageServiceImpl;
import org.apache.rocketmq.broker.util.ServiceProvider;
import org.apache.rocketmq.common.*;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.constant.PermName;
import org.apache.rocketmq.common.namesrv.RegisterBrokerResult;
import org.apache.rocketmq.common.protocol.RequestCode;
import org.apache.rocketmq.common.protocol.body.TopicConfigSerializeWrapper;
import org.apache.rocketmq.common.stats.MomentStatsItem;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.remoting.RPCHook;
import org.apache.rocketmq.remoting.RemotingServer;
import org.apache.rocketmq.remoting.common.TlsMode;
import org.apache.rocketmq.remoting.netty.*;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.apache.rocketmq.srvutil.FileWatchService;
import org.apache.rocketmq.store.DefaultMessageStore;
import org.apache.rocketmq.store.MessageArrivingListener;
import org.apache.rocketmq.store.MessageStore;
import org.apache.rocketmq.store.config.BrokerRole;
import org.apache.rocketmq.store.config.MessageStoreConfig;
import org.apache.rocketmq.store.dledger.DLedgerCommitLog;
import org.apache.rocketmq.store.stats.BrokerStats;
import org.apache.rocketmq.store.stats.BrokerStatsManager;
import org.apache.rocketmq.store.stats.LmqBrokerStatsManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;

public class BrokerController {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.BROKER_LOGGER_NAME);
    private static final InternalLogger LOG_PROTECTION = InternalLoggerFactory.getLogger(LoggerName.PROTECTION_LOGGER_NAME);
    private static final InternalLogger LOG_WATER_MARK = InternalLoggerFactory.getLogger(LoggerName.WATER_MARK_LOGGER_NAME);
    /**
     * broker配置
     */
    private final BrokerConfig brokerConfig;
    /**
     * netty配置
     */
    private final NettyServerConfig nettyServerConfig;
    /**
     * 网状客户端配置
     */
    private final NettyClientConfig nettyClientConfig;
    /**
     * 消息存储配置
     */
    private final MessageStoreConfig messageStoreConfig;
    /**
     * 消费端的offset管理
     */
    private final ConsumerOffsetManager consumerOffsetManager;
    /**
     * 消费者管理器
     */
    private final ConsumerManager consumerManager;
    /**
     * 消费者过滤器管理器
     */
    private final ConsumerFilterManager consumerFilterManager;
    /**
     * 生产者管理器
     */
    private final ProducerManager producerManager;
    private final ClientHousekeepingService clientHousekeepingService;
    /**
     * 拉取消息处理器
     */
    private final PullMessageProcessor pullMessageProcessor;
    /**
     * 拉取请求保存服务
     */
    private final PullRequestHoldService pullRequestHoldService;
    /**
     * 消息到达侦听器
     */
    private final MessageArrivingListener messageArrivingListener;
    private final Broker2Client broker2Client;
    /**
     * 订阅组管理器
     */
    private final SubscriptionGroupManager subscriptionGroupManager;
    /**
     * 消费者ids改变监听器
     */
    private final ConsumerIdsChangeListener consumerIdsChangeListener;
    /**
     * 平衡锁管理器
     */
    private final RebalanceLockManager rebalanceLockManager = new RebalanceLockManager();
    /**
     * broker外部api
     */
    private final BrokerOuterAPI brokerOuterAPI;
    /**
     * 计划执行者服务
     */
    private final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryImpl(
            "BrokerControllerScheduledThread"));
    /**
     * 从机同步
     */
    private final SlaveSynchronize slaveSynchronize;
    /**
     * 发送线程池队列
     */
    private final BlockingQueue<Runnable> sendThreadPoolQueue;
    /**
     * 放入线程池队列
     */
    private final BlockingQueue<Runnable> putThreadPoolQueue;
    /**
     * 拉取线程池队列
     */
    private final BlockingQueue<Runnable> pullThreadPoolQueue;
    /**
     * 重试队列线程池
     */
    private final BlockingQueue<Runnable> replyThreadPoolQueue;
    /**
     * 查询队列线程池
     */
    private final BlockingQueue<Runnable> queryThreadPoolQueue;
    /**
     * 客户机管理器线程池队列
     */
    private final BlockingQueue<Runnable> clientManagerThreadPoolQueue;
    /**
     * 心跳线程池队列
     */
    private final BlockingQueue<Runnable> heartbeatThreadPoolQueue;
    /**
     * 消费管理器线程池队列
     */
    private final BlockingQueue<Runnable> consumerManagerThreadPoolQueue;
    /**
     * 结束事务线程池队列
     */
    private final BlockingQueue<Runnable> endTransactionThreadPoolQueue;
    /**
     * 过滤服务器管理器
     */
    private final FilterServerManager filterServerManager;
    /**
     * broker 状态管理器
     */
    private final BrokerStatsManager brokerStatsManager;
    /**
     * 发送消息钩子列表
     */
    private final List<SendMessageHook> sendMessageHookList = new ArrayList<SendMessageHook>();
    /**
     * 消费消息钩子列表
     */
    private final List<ConsumeMessageHook> consumeMessageHookList = new ArrayList<ConsumeMessageHook>();
    /**
     * broker快速失败
     */
    private final BrokerFastFailure brokerFastFailure;
    /**
     * 配置
     */
    private final Configuration configuration;
    /**
     * 通过校验器 map
     */
    private final Map<Class, AccessValidator> accessValidatorMap = new HashMap<Class, AccessValidator>();
    /**
     * 消息存储
     */
    private MessageStore messageStore;
    /**
     * 远程服务器
     */
    private RemotingServer remotingServer;
    /**
     * 快速远程服务器
     */
    private RemotingServer fastRemotingServer;
    /**
     * 主题配置管理器
     */
    private TopicConfigManager topicConfigManager;
    /**
     * 发送消息执行器
     */
    private ExecutorService sendMessageExecutor;
    /**
     * 放入消息未来执行者
     */
    private ExecutorService putMessageFutureExecutor;
    /**
     * 拉取消息执行器
     */
    private ExecutorService pullMessageExecutor;
    /**
     * 重试消息执行器
     */
    private ExecutorService replyMessageExecutor;
    /**
     * 查询消息执行器
     */
    private ExecutorService queryMessageExecutor;
    /**
     * 管理broker 执行器
     */
    private ExecutorService adminBrokerExecutor;
    /**
     * 客户端管理执行器
     */
    private ExecutorService clientManageExecutor;
    /**
     * 心跳执行器
     */
    private ExecutorService heartbeatExecutor;
    /**
     * 消费管理执行器
     */
    private ExecutorService consumerManageExecutor;
    /**
     * 结束事务执行器
     */
    private ExecutorService endTransactionExecutor;
    private boolean updateMasterHAServerAddrPeriodically = false;
    /**
     * broker状态
     */
    private BrokerStats brokerStats;
    /**
     * 存储主机
     */
    private InetSocketAddress storeHost;
    /**
     * 文件监控服务
     */
    private FileWatchService fileWatchService;
    /**
     * 事务性消息检查服务
     */
    private TransactionalMessageCheckService transactionalMessageCheckService;
    /**
     * 事务性消息服务
     */
    private TransactionalMessageService transactionalMessageService;
    /**
     * 事务性消息检查侦听器
     */
    private AbstractTransactionalMessageCheckListener transactionalMessageCheckListener;
    /**
     * 从机同步
     */
    private Future<?> slaveSyncFuture;

    public BrokerController(
            final BrokerConfig brokerConfig,
            final NettyServerConfig nettyServerConfig,
            final NettyClientConfig nettyClientConfig,
            final MessageStoreConfig messageStoreConfig
    ) {
        this.brokerConfig = brokerConfig;
        this.nettyServerConfig = nettyServerConfig;
        this.nettyClientConfig = nettyClientConfig;
        this.messageStoreConfig = messageStoreConfig;
        this.consumerOffsetManager = messageStoreConfig.isEnableLmq() ? new LmqConsumerOffsetManager(this) : new ConsumerOffsetManager(this);
        this.topicConfigManager = messageStoreConfig.isEnableLmq() ? new LmqTopicConfigManager(this) : new TopicConfigManager(this);
        this.pullMessageProcessor = new PullMessageProcessor(this);
        this.pullRequestHoldService = messageStoreConfig.isEnableLmq() ? new LmqPullRequestHoldService(this) : new PullRequestHoldService(this);
        this.messageArrivingListener = new NotifyMessageArrivingListener(this.pullRequestHoldService);
        this.consumerIdsChangeListener = new DefaultConsumerIdsChangeListener(this);
        this.consumerManager = new ConsumerManager(this.consumerIdsChangeListener);
        this.consumerFilterManager = new ConsumerFilterManager(this);
        this.producerManager = new ProducerManager();
        this.clientHousekeepingService = new ClientHousekeepingService(this);
        this.broker2Client = new Broker2Client(this);
        this.subscriptionGroupManager = messageStoreConfig.isEnableLmq() ? new LmqSubscriptionGroupManager(this) : new SubscriptionGroupManager(this);
        this.brokerOuterAPI = new BrokerOuterAPI(nettyClientConfig);
        this.filterServerManager = new FilterServerManager(this);

        this.slaveSynchronize = new SlaveSynchronize(this);

        this.sendThreadPoolQueue = new LinkedBlockingQueue<>(this.brokerConfig.getSendThreadPoolQueueCapacity());
        this.putThreadPoolQueue = new LinkedBlockingQueue<>(this.brokerConfig.getPutThreadPoolQueueCapacity());
        this.pullThreadPoolQueue = new LinkedBlockingQueue<>(this.brokerConfig.getPullThreadPoolQueueCapacity());
        this.replyThreadPoolQueue = new LinkedBlockingQueue<>(this.brokerConfig.getReplyThreadPoolQueueCapacity());
        this.queryThreadPoolQueue = new LinkedBlockingQueue<>(this.brokerConfig.getQueryThreadPoolQueueCapacity());
        this.clientManagerThreadPoolQueue = new LinkedBlockingQueue<>(this.brokerConfig.getClientManagerThreadPoolQueueCapacity());
        this.consumerManagerThreadPoolQueue = new LinkedBlockingQueue<>(this.brokerConfig.getConsumerManagerThreadPoolQueueCapacity());
        this.heartbeatThreadPoolQueue = new LinkedBlockingQueue<>(this.brokerConfig.getHeartbeatThreadPoolQueueCapacity());
        this.endTransactionThreadPoolQueue = new LinkedBlockingQueue<>(this.brokerConfig.getEndTransactionPoolQueueCapacity());

        this.brokerStatsManager = messageStoreConfig.isEnableLmq() ? new LmqBrokerStatsManager(this.brokerConfig.getBrokerClusterName(), this.brokerConfig.isEnableDetailStat()) : new BrokerStatsManager(this.brokerConfig.getBrokerClusterName(), this.brokerConfig.isEnableDetailStat());

        this.setStoreHost(new InetSocketAddress(this.getBrokerConfig().getBrokerIP1(), this.getNettyServerConfig().getListenPort()));

        this.brokerFastFailure = new BrokerFastFailure(this);
        this.configuration = new Configuration(
                log,
                BrokerPathConfigHelper.getBrokerConfigPath(),
                this.brokerConfig, this.nettyServerConfig, this.nettyClientConfig, this.messageStoreConfig
        );
    }

    public BrokerConfig getBrokerConfig() {
        return brokerConfig;
    }

    public NettyServerConfig getNettyServerConfig() {
        return nettyServerConfig;
    }

    public BlockingQueue<Runnable> getPullThreadPoolQueue() {
        return pullThreadPoolQueue;
    }

    public BlockingQueue<Runnable> getQueryThreadPoolQueue() {
        return queryThreadPoolQueue;
    }

    public boolean initialize() throws CloneNotSupportedException {
        // 加载 topic
        boolean result = this.topicConfigManager.load();
        // 加载 消费者偏移量
        result = result && this.consumerOffsetManager.load();
        // 加载 订阅组
        result = result && this.subscriptionGroupManager.load();
        // 加载 消费者过滤器
        result = result && this.consumerFilterManager.load();
        // 加载 消息存储
        if (result) {
            try {
                // 加载消息存储
                this.messageStore =
                        new DefaultMessageStore(this.messageStoreConfig, this.brokerStatsManager, this.messageArrivingListener,
                                this.brokerConfig);
                // 加载 DLedger
                if (messageStoreConfig.isEnableDLegerCommitLog()) {
                    DLedgerRoleChangeHandler roleChangeHandler = new DLedgerRoleChangeHandler(this, (DefaultMessageStore) messageStore);
                    ((DLedgerCommitLog) ((DefaultMessageStore) messageStore).getCommitLog()).getdLedgerServer().getdLedgerLeaderElector().addRoleChangeHandler(roleChangeHandler);
                }
                // 加载 BrokerStats
                this.brokerStats = new BrokerStats((DefaultMessageStore) this.messageStore);
                //load plugin
                MessageStorePluginContext context = new MessageStorePluginContext(messageStoreConfig, brokerStatsManager, messageArrivingListener, brokerConfig);
                this.messageStore = MessageStoreFactory.build(context, this.messageStore);
                this.messageStore.getDispatcherList().addFirst(new CommitLogDispatcherCalcBitMap(this.brokerConfig, this.consumerFilterManager));
            } catch (IOException e) {
                result = false;
                log.error("Failed to initialize", e);
            }
        }
        // 加载消息
        result = result && this.messageStore.load();
        // 创建线程池
        if (result) {
            // 创建server
            this.remotingServer = new NettyRemotingServer(this.nettyServerConfig, this.clientHousekeepingService);
            NettyServerConfig fastConfig = (NettyServerConfig) this.nettyServerConfig.clone();
            fastConfig.setListenPort(nettyServerConfig.getListenPort() - 2);
            this.fastRemotingServer = new NettyRemotingServer(fastConfig, this.clientHousekeepingService);
            // 创建发送线程池执行器
            this.sendMessageExecutor = new BrokerFixedThreadPoolExecutor(
                    this.brokerConfig.getSendMessageThreadPoolNums(),
                    this.brokerConfig.getSendMessageThreadPoolNums(),
                    1000 * 60,
                    TimeUnit.MILLISECONDS,
                    this.sendThreadPoolQueue,
                    new ThreadFactoryImpl("SendMessageThread_"));
            // 创建放入线程池执行器
            this.putMessageFutureExecutor = new BrokerFixedThreadPoolExecutor(
                    this.brokerConfig.getPutMessageFutureThreadPoolNums(),
                    this.brokerConfig.getPutMessageFutureThreadPoolNums(),
                    1000 * 60,
                    TimeUnit.MILLISECONDS,
                    this.putThreadPoolQueue,
                    new ThreadFactoryImpl("PutMessageThread_"));
            // 创建拉取线程池执行器
            this.pullMessageExecutor = new BrokerFixedThreadPoolExecutor(
                    this.brokerConfig.getPullMessageThreadPoolNums(),
                    this.brokerConfig.getPullMessageThreadPoolNums(),
                    1000 * 60,
                    TimeUnit.MILLISECONDS,
                    this.pullThreadPoolQueue,
                    new ThreadFactoryImpl("PullMessageThread_"));
            // 创建重试线程池执行器
            this.replyMessageExecutor = new BrokerFixedThreadPoolExecutor(
                    this.brokerConfig.getProcessReplyMessageThreadPoolNums(),
                    this.brokerConfig.getProcessReplyMessageThreadPoolNums(),
                    1000 * 60,
                    TimeUnit.MILLISECONDS,
                    this.replyThreadPoolQueue,
                    new ThreadFactoryImpl("ProcessReplyMessageThread_"));
            // 创建查询线程池执行器
            this.queryMessageExecutor = new BrokerFixedThreadPoolExecutor(
                    this.brokerConfig.getQueryMessageThreadPoolNums(),
                    this.brokerConfig.getQueryMessageThreadPoolNums(),
                    1000 * 60,
                    TimeUnit.MILLISECONDS,
                    this.queryThreadPoolQueue,
                    new ThreadFactoryImpl("QueryMessageThread_"));
            // 创建管理 broker 线程池执行器
            this.adminBrokerExecutor =
                    Executors.newFixedThreadPool(this.brokerConfig.getAdminBrokerThreadPoolNums(), new ThreadFactoryImpl(
                            "AdminBrokerThread_"));
            // 创建管理 client 线程池执行器
            this.clientManageExecutor = new ThreadPoolExecutor(
                    this.brokerConfig.getClientManageThreadPoolNums(),
                    this.brokerConfig.getClientManageThreadPoolNums(),
                    1000 * 60,
                    TimeUnit.MILLISECONDS,
                    this.clientManagerThreadPoolQueue,
                    new ThreadFactoryImpl("ClientManageThread_"));
            // 创建心跳线程池执行器
            this.heartbeatExecutor = new BrokerFixedThreadPoolExecutor(
                    this.brokerConfig.getHeartbeatThreadPoolNums(),
                    this.brokerConfig.getHeartbeatThreadPoolNums(),
                    1000 * 60,
                    TimeUnit.MILLISECONDS,
                    this.heartbeatThreadPoolQueue,
                    new ThreadFactoryImpl("HeartbeatThread_", true));
            // 创建结束事件线程池执行器
            this.endTransactionExecutor = new BrokerFixedThreadPoolExecutor(
                    this.brokerConfig.getEndTransactionThreadPoolNums(),
                    this.brokerConfig.getEndTransactionThreadPoolNums(),
                    1000 * 60,
                    TimeUnit.MILLISECONDS,
                    this.endTransactionThreadPoolQueue,
                    new ThreadFactoryImpl("EndTransactionThread_"));
            // 创建消费者管理线程池执行器
            this.consumerManageExecutor =
                    Executors.newFixedThreadPool(this.brokerConfig.getConsumerManageThreadPoolNums(), new ThreadFactoryImpl(
                            "ConsumerManageThread_"));
            // 注册 Processor
            this.registerProcessor();
            final long initialDelay = UtilAll.computeNextMorningTimeMillis() - System.currentTimeMillis();
            final long period = 1000 * 60 * 60 * 24;
            // 定时任务
            this.scheduledExecutorService.scheduleAtFixedRate(() -> {
                try {
                    BrokerController.this.getBrokerStats().record();
                } catch (Throwable e) {
                    log.error("schedule record error.", e);
                }
            }, initialDelay, period, TimeUnit.MILLISECONDS);
            // 定时任务
            this.scheduledExecutorService.scheduleAtFixedRate(() -> {
                try {
                    BrokerController.this.consumerOffsetManager.persist();
                } catch (Throwable e) {
                    log.error("schedule persist consumerOffset error.", e);
                }
            }, 1000 * 10, this.brokerConfig.getFlushConsumerOffsetInterval(), TimeUnit.MILLISECONDS);
            // 定时任务
            this.scheduledExecutorService.scheduleAtFixedRate(() -> {
                try {
                    BrokerController.this.consumerFilterManager.persist();
                } catch (Throwable e) {
                    log.error("schedule persist consumer filter error.", e);
                }
            }, 1000 * 10, 1000 * 10, TimeUnit.MILLISECONDS);
            // 定时任务
            this.scheduledExecutorService.scheduleAtFixedRate(() -> {
                try {
                    BrokerController.this.protectBroker();
                } catch (Throwable e) {
                    log.error("protectBroker error.", e);
                }
            }, 3, 3, TimeUnit.MINUTES);
            // 定时任务
            this.scheduledExecutorService.scheduleAtFixedRate(() -> {
                try {
                    BrokerController.this.printWaterMark();
                } catch (Throwable e) {
                    log.error("printWaterMark error.", e);
                }
            }, 10, 1, TimeUnit.SECONDS);
            // 定时任务
            this.scheduledExecutorService.scheduleAtFixedRate(() -> {
                try {
                    log.info("dispatch behind commit log {} bytes", BrokerController.this.getMessageStore().dispatchBehindBytes());
                } catch (Throwable e) {
                    log.error("schedule dispatchBehindBytes error.", e);
                }
            }, 1000 * 10, 1000 * 60, TimeUnit.MILLISECONDS);

            if (this.brokerConfig.getNamesrvAddr() != null) {
                this.brokerOuterAPI.updateNameServerAddressList(this.brokerConfig.getNamesrvAddr());
                log.info("Set user specified name server address: {}", this.brokerConfig.getNamesrvAddr());
                // 定时任务
                this.scheduledExecutorService.scheduleAtFixedRate(() -> {
                    try {
                        BrokerController.this.brokerOuterAPI.updateNameServerAddressList(BrokerController.this.brokerConfig.getNamesrvAddr());
                    } catch (Throwable e) {
                        log.error("ScheduledTask updateNameServerAddr exception", e);
                    }
                }, 1000 * 10, 1000 * 60 * 2, TimeUnit.MILLISECONDS);
            } else if (this.brokerConfig.isFetchNamesrvAddrByAddressServer()) {
                // 定时任务
                this.scheduledExecutorService.scheduleAtFixedRate(() -> {
                    try {
                        BrokerController.this.brokerOuterAPI.fetchNameServerAddr();
                    } catch (Throwable e) {
                        log.error("ScheduledTask fetchNameServerAddr exception", e);
                    }
                }, 1000 * 10, 1000 * 60 * 2, TimeUnit.MILLISECONDS);
            }

            if (!messageStoreConfig.isEnableDLegerCommitLog()) {
                if (BrokerRole.SLAVE == this.messageStoreConfig.getBrokerRole()) {
                    if (this.messageStoreConfig.getHaMasterAddress() != null && this.messageStoreConfig.getHaMasterAddress().length() >= 6) {
                        this.messageStore.updateHaMasterAddress(this.messageStoreConfig.getHaMasterAddress());
                        this.updateMasterHAServerAddrPeriodically = false;
                    } else {
                        this.updateMasterHAServerAddrPeriodically = true;
                    }
                } else {
                    this.scheduledExecutorService.scheduleAtFixedRate(() -> {
                        try {
                            BrokerController.this.printMasterAndSlaveDiff();
                        } catch (Throwable e) {
                            log.error("schedule printMasterAndSlaveDiff error.", e);
                        }
                    }, 1000 * 10, 1000 * 60, TimeUnit.MILLISECONDS);
                }
            }
            // TLS文件监控
            if (TlsSystemConfig.tlsMode != TlsMode.DISABLED) {
                // Register a listener to reload SslContext
                try {
                    fileWatchService = new FileWatchService(
                            new String[]{
                                    TlsSystemConfig.tlsServerCertPath,
                                    TlsSystemConfig.tlsServerKeyPath,
                                    TlsSystemConfig.tlsServerTrustCertPath
                            },
                            new FileWatchService.Listener() {
                                boolean certChanged, keyChanged = false;

                                @Override
                                public void onChanged(String path) {
                                    if (path.equals(TlsSystemConfig.tlsServerTrustCertPath)) {
                                        log.info("The trust certificate changed, reload the ssl context");
                                        reloadServerSslContext();
                                    }
                                    if (path.equals(TlsSystemConfig.tlsServerCertPath)) {
                                        certChanged = true;
                                    }
                                    if (path.equals(TlsSystemConfig.tlsServerKeyPath)) {
                                        keyChanged = true;
                                    }
                                    if (certChanged && keyChanged) {
                                        log.info("The certificate and private key changed, reload the ssl context");
                                        certChanged = keyChanged = false;
                                        reloadServerSslContext();
                                    }
                                }

                                private void reloadServerSslContext() {
                                    ((NettyRemotingServer) remotingServer).loadSslContext();
                                    ((NettyRemotingServer) fastRemotingServer).loadSslContext();
                                }
                            });
                } catch (Exception e) {
                    log.warn("FileWatchService created error, can't load the certificate dynamically");
                }
            }
            // 初始化事务
            initialTransaction();
            // 初始化 ACL
            initialAcl();
            // 初始化 RPCHook
            initialRpcHooks();
        }
        return result;
    }

    /**
     * 初始事务, 事务消息服务, 事务消息检查服务, 事务消息检查监听器
     */
    private void initialTransaction() {
        this.transactionalMessageService = ServiceProvider.loadClass(ServiceProvider.TRANSACTION_SERVICE_ID, TransactionalMessageService.class);
        if (null == this.transactionalMessageService) {
            this.transactionalMessageService = new TransactionalMessageServiceImpl(new TransactionalMessageBridge(this, this.getMessageStore()));
            log.warn("Load default transaction message hook service: {}", TransactionalMessageServiceImpl.class.getSimpleName());
        }
        this.transactionalMessageCheckListener = ServiceProvider.loadClass(ServiceProvider.TRANSACTION_LISTENER_ID, AbstractTransactionalMessageCheckListener.class);
        if (null == this.transactionalMessageCheckListener) {
            this.transactionalMessageCheckListener = new DefaultTransactionalMessageCheckListener();
            log.warn("Load default discard message hook service: {}", DefaultTransactionalMessageCheckListener.class.getSimpleName());
        }
        this.transactionalMessageCheckListener.setBrokerController(this);
        this.transactionalMessageCheckService = new TransactionalMessageCheckService(this);
    }

    /**
     * 初始化 acl，初始化校验器，注册 RPCHook
     */
    private void initialAcl() {
        if (!this.brokerConfig.isAclEnable()) {
            log.info("The broker dose not enable acl");
            return;
        }

        List<AccessValidator> accessValidators = ServiceProvider.load(ServiceProvider.ACL_VALIDATOR_ID, AccessValidator.class);
        if (accessValidators == null || accessValidators.isEmpty()) {
            log.info("The broker dose not load the AccessValidator");
            return;
        }

        for (AccessValidator accessValidator : accessValidators) {
            final AccessValidator validator = accessValidator;
            accessValidatorMap.put(validator.getClass(), validator);
            this.registerServerRPCHook(new RPCHook() {

                @Override
                public void doBeforeRequest(String remoteAddr, RemotingCommand request) {
                    //Do not catch the exception
                    validator.validate(validator.parse(request, remoteAddr));
                }

                @Override
                public void doAfterResponse(String remoteAddr, RemotingCommand request, RemotingCommand response) {
                }
            });
        }
    }


    /**
     * 初始化rpc钩子，加载 RPCHook
     */
    private void initialRpcHooks() {

        List<RPCHook> rpcHooks = ServiceProvider.load(ServiceProvider.RPC_HOOK_ID, RPCHook.class);
        if (rpcHooks == null || rpcHooks.isEmpty()) {
            return;
        }
        for (RPCHook rpcHook : rpcHooks) {
            this.registerServerRPCHook(rpcHook);
        }
    }

    /**
     * 为 remotingServer 和 fastRemotingServer 注册处理器
     */
    public void registerProcessor() {
        /**
         * SendMessageProcessor
         */
        SendMessageProcessor sendProcessor = new SendMessageProcessor(this);
        sendProcessor.registerSendMessageHook(sendMessageHookList);
        sendProcessor.registerConsumeMessageHook(consumeMessageHookList);

        this.remotingServer.registerProcessor(RequestCode.SEND_MESSAGE, sendProcessor, this.sendMessageExecutor);
        this.remotingServer.registerProcessor(RequestCode.SEND_MESSAGE_V2, sendProcessor, this.sendMessageExecutor);
        this.remotingServer.registerProcessor(RequestCode.SEND_BATCH_MESSAGE, sendProcessor, this.sendMessageExecutor);
        this.remotingServer.registerProcessor(RequestCode.CONSUMER_SEND_MSG_BACK, sendProcessor, this.sendMessageExecutor);
        this.fastRemotingServer.registerProcessor(RequestCode.SEND_MESSAGE, sendProcessor, this.sendMessageExecutor);
        this.fastRemotingServer.registerProcessor(RequestCode.SEND_MESSAGE_V2, sendProcessor, this.sendMessageExecutor);
        this.fastRemotingServer.registerProcessor(RequestCode.SEND_BATCH_MESSAGE, sendProcessor, this.sendMessageExecutor);
        this.fastRemotingServer.registerProcessor(RequestCode.CONSUMER_SEND_MSG_BACK, sendProcessor, this.sendMessageExecutor);
        /**
         * PullMessageProcessor
         */
        this.remotingServer.registerProcessor(RequestCode.PULL_MESSAGE, this.pullMessageProcessor, this.pullMessageExecutor);
        this.pullMessageProcessor.registerConsumeMessageHook(consumeMessageHookList);

        /**
         * ReplyMessageProcessor
         */
        ReplyMessageProcessor replyMessageProcessor = new ReplyMessageProcessor(this);
        replyMessageProcessor.registerSendMessageHook(sendMessageHookList);

        this.remotingServer.registerProcessor(RequestCode.SEND_REPLY_MESSAGE, replyMessageProcessor, replyMessageExecutor);
        this.remotingServer.registerProcessor(RequestCode.SEND_REPLY_MESSAGE_V2, replyMessageProcessor, replyMessageExecutor);
        this.fastRemotingServer.registerProcessor(RequestCode.SEND_REPLY_MESSAGE, replyMessageProcessor, replyMessageExecutor);
        this.fastRemotingServer.registerProcessor(RequestCode.SEND_REPLY_MESSAGE_V2, replyMessageProcessor, replyMessageExecutor);

        /**
         * QueryMessageProcessor
         */
        NettyRequestProcessor queryProcessor = new QueryMessageProcessor(this);
        this.remotingServer.registerProcessor(RequestCode.QUERY_MESSAGE, queryProcessor, this.queryMessageExecutor);
        this.remotingServer.registerProcessor(RequestCode.VIEW_MESSAGE_BY_ID, queryProcessor, this.queryMessageExecutor);

        this.fastRemotingServer.registerProcessor(RequestCode.QUERY_MESSAGE, queryProcessor, this.queryMessageExecutor);
        this.fastRemotingServer.registerProcessor(RequestCode.VIEW_MESSAGE_BY_ID, queryProcessor, this.queryMessageExecutor);

        /**
         * ClientManageProcessor
         */
        ClientManageProcessor clientProcessor = new ClientManageProcessor(this);
        this.remotingServer.registerProcessor(RequestCode.HEART_BEAT, clientProcessor, this.heartbeatExecutor);
        this.remotingServer.registerProcessor(RequestCode.UNREGISTER_CLIENT, clientProcessor, this.clientManageExecutor);
        this.remotingServer.registerProcessor(RequestCode.CHECK_CLIENT_CONFIG, clientProcessor, this.clientManageExecutor);

        this.fastRemotingServer.registerProcessor(RequestCode.HEART_BEAT, clientProcessor, this.heartbeatExecutor);
        this.fastRemotingServer.registerProcessor(RequestCode.UNREGISTER_CLIENT, clientProcessor, this.clientManageExecutor);
        this.fastRemotingServer.registerProcessor(RequestCode.CHECK_CLIENT_CONFIG, clientProcessor, this.clientManageExecutor);

        /**
         * ConsumerManageProcessor
         */
        ConsumerManageProcessor consumerManageProcessor = new ConsumerManageProcessor(this);
        this.remotingServer.registerProcessor(RequestCode.GET_CONSUMER_LIST_BY_GROUP, consumerManageProcessor, this.consumerManageExecutor);
        this.remotingServer.registerProcessor(RequestCode.UPDATE_CONSUMER_OFFSET, consumerManageProcessor, this.consumerManageExecutor);
        this.remotingServer.registerProcessor(RequestCode.QUERY_CONSUMER_OFFSET, consumerManageProcessor, this.consumerManageExecutor);

        this.fastRemotingServer.registerProcessor(RequestCode.GET_CONSUMER_LIST_BY_GROUP, consumerManageProcessor, this.consumerManageExecutor);
        this.fastRemotingServer.registerProcessor(RequestCode.UPDATE_CONSUMER_OFFSET, consumerManageProcessor, this.consumerManageExecutor);
        this.fastRemotingServer.registerProcessor(RequestCode.QUERY_CONSUMER_OFFSET, consumerManageProcessor, this.consumerManageExecutor);

        /**
         * EndTransactionProcessor
         */
        this.remotingServer.registerProcessor(RequestCode.END_TRANSACTION, new EndTransactionProcessor(this), this.endTransactionExecutor);
        this.fastRemotingServer.registerProcessor(RequestCode.END_TRANSACTION, new EndTransactionProcessor(this), this.endTransactionExecutor);

        /**
         * Default
         */
        AdminBrokerProcessor adminProcessor = new AdminBrokerProcessor(this);
        this.remotingServer.registerDefaultProcessor(adminProcessor, this.adminBrokerExecutor);
        this.fastRemotingServer.registerDefaultProcessor(adminProcessor, this.adminBrokerExecutor);
    }

    public BrokerStats getBrokerStats() {
        return brokerStats;
    }

    public void setBrokerStats(BrokerStats brokerStats) {
        this.brokerStats = brokerStats;
    }

    /**
     * broker保护机制
     */
    public void protectBroker() {
        if (this.brokerConfig.isDisableConsumeIfConsumerReadSlowly()) {
            final Iterator<Map.Entry<String, MomentStatsItem>> it = this.brokerStatsManager.getMomentStatsItemSetFallSize().getStatsItemTable().entrySet().iterator();
            while (it.hasNext()) {
                final Map.Entry<String, MomentStatsItem> next = it.next();
                final long fallBehindBytes = next.getValue().getValue().get();
                if (fallBehindBytes > this.brokerConfig.getConsumerFallbehindThreshold()) {
                    final String[] split = next.getValue().getStatsKey().split("@");
                    final String group = split[2];
                    LOG_PROTECTION.info("[PROTECT_BROKER] the consumer[{}] consume slowly, {} bytes, disable it", group, fallBehindBytes);
                    this.subscriptionGroupManager.disableConsume(group);
                }
            }
        }
    }

    public long headSlowTimeMills(BlockingQueue<Runnable> q) {
        long slowTimeMills = 0;
        final Runnable peek = q.peek();
        if (peek != null) {
            RequestTask rt = BrokerFastFailure.castRunnable(peek);
            slowTimeMills = rt == null ? 0 : this.messageStore.now() - rt.getCreateTimestamp();
        }

        if (slowTimeMills < 0) {
            slowTimeMills = 0;
        }

        return slowTimeMills;
    }

    public long headSlowTimeMills4SendThreadPoolQueue() {
        return this.headSlowTimeMills(this.sendThreadPoolQueue);
    }

    public long headSlowTimeMills4PullThreadPoolQueue() {
        return this.headSlowTimeMills(this.pullThreadPoolQueue);
    }

    public long headSlowTimeMills4QueryThreadPoolQueue() {
        return this.headSlowTimeMills(this.queryThreadPoolQueue);
    }

    public long headSlowTimeMills4EndTransactionThreadPoolQueue() {
        return this.headSlowTimeMills(this.endTransactionThreadPoolQueue);
    }

    public void printWaterMark() {
        LOG_WATER_MARK.info("[WATERMARK] Send Queue Size: {} SlowTimeMills: {}", this.sendThreadPoolQueue.size(), headSlowTimeMills4SendThreadPoolQueue());
        LOG_WATER_MARK.info("[WATERMARK] Pull Queue Size: {} SlowTimeMills: {}", this.pullThreadPoolQueue.size(), headSlowTimeMills4PullThreadPoolQueue());
        LOG_WATER_MARK.info("[WATERMARK] Query Queue Size: {} SlowTimeMills: {}", this.queryThreadPoolQueue.size(), headSlowTimeMills4QueryThreadPoolQueue());
        LOG_WATER_MARK.info("[WATERMARK] Transaction Queue Size: {} SlowTimeMills: {}", this.endTransactionThreadPoolQueue.size(), headSlowTimeMills4EndTransactionThreadPoolQueue());
    }

    public MessageStore getMessageStore() {
        return messageStore;
    }

    public void setMessageStore(MessageStore messageStore) {
        this.messageStore = messageStore;
    }

    private void printMasterAndSlaveDiff() {
        long diff = this.messageStore.slaveFallBehindMuch();

        // XXX: warn and notify me
        log.info("Slave fall behind master: {} bytes", diff);
    }

    public Broker2Client getBroker2Client() {
        return broker2Client;
    }

    public ConsumerManager getConsumerManager() {
        return consumerManager;
    }

    public ConsumerFilterManager getConsumerFilterManager() {
        return consumerFilterManager;
    }

    public ConsumerOffsetManager getConsumerOffsetManager() {
        return consumerOffsetManager;
    }

    public MessageStoreConfig getMessageStoreConfig() {
        return messageStoreConfig;
    }

    public ProducerManager getProducerManager() {
        return producerManager;
    }

    public void setFastRemotingServer(RemotingServer fastRemotingServer) {
        this.fastRemotingServer = fastRemotingServer;
    }

    public PullMessageProcessor getPullMessageProcessor() {
        return pullMessageProcessor;
    }

    public PullRequestHoldService getPullRequestHoldService() {
        return pullRequestHoldService;
    }

    public SubscriptionGroupManager getSubscriptionGroupManager() {
        return subscriptionGroupManager;
    }

    public void shutdown() {
        if (this.brokerStatsManager != null) {
            this.brokerStatsManager.shutdown();
        }

        if (this.clientHousekeepingService != null) {
            this.clientHousekeepingService.shutdown();
        }

        if (this.pullRequestHoldService != null) {
            this.pullRequestHoldService.shutdown();
        }

        if (this.remotingServer != null) {
            this.remotingServer.shutdown();
        }

        if (this.fastRemotingServer != null) {
            this.fastRemotingServer.shutdown();
        }

        if (this.fileWatchService != null) {
            this.fileWatchService.shutdown();
        }

        if (this.messageStore != null) {
            this.messageStore.shutdown();
        }

        this.scheduledExecutorService.shutdown();
        try {
            this.scheduledExecutorService.awaitTermination(5000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
        }

        this.unregisterBrokerAll();

        if (this.sendMessageExecutor != null) {
            this.sendMessageExecutor.shutdown();
        }

        if (this.putMessageFutureExecutor != null) {
            this.putMessageFutureExecutor.shutdown();
        }

        if (this.pullMessageExecutor != null) {
            this.pullMessageExecutor.shutdown();
        }

        if (this.replyMessageExecutor != null) {
            this.replyMessageExecutor.shutdown();
        }

        if (this.adminBrokerExecutor != null) {
            this.adminBrokerExecutor.shutdown();
        }

        if (this.brokerOuterAPI != null) {
            this.brokerOuterAPI.shutdown();
        }

        this.consumerOffsetManager.persist();

        if (this.filterServerManager != null) {
            this.filterServerManager.shutdown();
        }

        if (this.brokerFastFailure != null) {
            this.brokerFastFailure.shutdown();
        }

        if (this.consumerFilterManager != null) {
            this.consumerFilterManager.persist();
        }

        if (this.clientManageExecutor != null) {
            this.clientManageExecutor.shutdown();
        }

        if (this.queryMessageExecutor != null) {
            this.queryMessageExecutor.shutdown();
        }

        if (this.consumerManageExecutor != null) {
            this.consumerManageExecutor.shutdown();
        }

        if (this.fileWatchService != null) {
            this.fileWatchService.shutdown();
        }
        if (this.transactionalMessageCheckService != null) {
            this.transactionalMessageCheckService.shutdown(false);
        }

        if (this.endTransactionExecutor != null) {
            this.endTransactionExecutor.shutdown();
        }
    }

    private void unregisterBrokerAll() {
        this.brokerOuterAPI.unregisterBrokerAll(
                this.brokerConfig.getBrokerClusterName(),
                this.getBrokerAddr(),
                this.brokerConfig.getBrokerName(),
                this.brokerConfig.getBrokerId());
    }

    public String getBrokerAddr() {
        return this.brokerConfig.getBrokerIP1() + ":" + this.nettyServerConfig.getListenPort();
    }

    public void start() throws Exception {
        // 启动消息持久化
        if (this.messageStore != null) {
            this.messageStore.start();
        }
        // 启动远程服务
        if (this.remotingServer != null) {
            this.remotingServer.start();
        }
        // 启动快速远程服务
        if (this.fastRemotingServer != null) {
            this.fastRemotingServer.start();
        }
        // 启动文件监控服务
        if (this.fileWatchService != null) {
            this.fileWatchService.start();
        }
        // 启动定时任务
        if (this.brokerOuterAPI != null) {
            this.brokerOuterAPI.start();
        }
        // 启动消息拉取服务
        if (this.pullRequestHoldService != null) {
            this.pullRequestHoldService.start();
        }
        // 启动 服务
        if (this.clientHousekeepingService != null) {
            this.clientHousekeepingService.start();
        }
        // 启动消息过滤服务
        if (this.filterServerManager != null) {
            this.filterServerManager.start();
        }
        // 启动
        if (!messageStoreConfig.isEnableDLegerCommitLog()) {
            startProcessorByHa(messageStoreConfig.getBrokerRole());
            handleSlaveSynchronize(messageStoreConfig.getBrokerRole());
            this.registerBrokerAll(true, false, true);
        }
        //  每隔 10s ~ 60s 注册一次broker
        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

            @Override
            public void run() {
                try {
                    BrokerController.this.registerBrokerAll(true, false, brokerConfig.isForceRegister());
                } catch (Throwable e) {
                    log.error("registerBrokerAll Exception", e);
                }
            }
        }, 1000 * 10, Math.max(10000, Math.min(brokerConfig.getRegisterNameServerPeriod(), 60000)), TimeUnit.MILLISECONDS);
        // 启动 broker 管理服务
        if (this.brokerStatsManager != null) {
            this.brokerStatsManager.start();
        }
        // 启动 broker 快速失败服务
        if (this.brokerFastFailure != null) {
            this.brokerFastFailure.start();
        }


    }

    public synchronized void registerIncrementBrokerData(TopicConfig topicConfig, DataVersion dataVersion) {
        TopicConfig registerTopicConfig = topicConfig;
        if (!PermName.isWriteable(this.getBrokerConfig().getBrokerPermission())
                || !PermName.isReadable(this.getBrokerConfig().getBrokerPermission())) {
            registerTopicConfig =
                    new TopicConfig(topicConfig.getTopicName(), topicConfig.getReadQueueNums(), topicConfig.getWriteQueueNums(),
                            this.brokerConfig.getBrokerPermission());
        }

        ConcurrentMap<String, TopicConfig> topicConfigTable = new ConcurrentHashMap<String, TopicConfig>();
        topicConfigTable.put(topicConfig.getTopicName(), registerTopicConfig);
        TopicConfigSerializeWrapper topicConfigSerializeWrapper = new TopicConfigSerializeWrapper();
        topicConfigSerializeWrapper.setDataVersion(dataVersion);
        topicConfigSerializeWrapper.setTopicConfigTable(topicConfigTable);

        doRegisterBrokerAll(true, false, topicConfigSerializeWrapper);
    }

    /**
     * 注册所有的 broker，构建装饰器并进行判断是否需要注册，若不需要则不进行注册，若是需要则进行注册
     *
     * @param checkOrderConfig 检查顺序配置
     * @param oneway           单行
     * @param forceRegister    力注册
     */
    public synchronized void registerBrokerAll(final boolean checkOrderConfig, boolean oneway, boolean forceRegister) {
        TopicConfigSerializeWrapper topicConfigWrapper = this.getTopicConfigManager().buildTopicConfigSerializeWrapper();

        if (!PermName.isWriteable(this.getBrokerConfig().getBrokerPermission())
                || !PermName.isReadable(this.getBrokerConfig().getBrokerPermission())) {
            ConcurrentHashMap<String, TopicConfig> topicConfigTable = new ConcurrentHashMap<>();
            for (TopicConfig topicConfig : topicConfigWrapper.getTopicConfigTable().values()) {
                TopicConfig tmp =
                        new TopicConfig(topicConfig.getTopicName(), topicConfig.getReadQueueNums(), topicConfig.getWriteQueueNums(),
                                this.brokerConfig.getBrokerPermission());
                topicConfigTable.put(topicConfig.getTopicName(), tmp);
            }
            topicConfigWrapper.setTopicConfigTable(topicConfigTable);
        }

        if (forceRegister || needRegister(this.brokerConfig.getBrokerClusterName(),
                this.getBrokerAddr(),
                this.brokerConfig.getBrokerName(),
                this.brokerConfig.getBrokerId(),
                this.brokerConfig.getRegisterBrokerTimeoutMills())) {
            doRegisterBrokerAll(checkOrderConfig, oneway, topicConfigWrapper);
        }
    }

    /**
     * 执行broker 注册
     *
     * @param checkOrderConfig   检查顺序配置
     * @param oneway             单行
     * @param topicConfigWrapper 主题配置包装
     */
    private void doRegisterBrokerAll(boolean checkOrderConfig, boolean oneway,
                                     TopicConfigSerializeWrapper topicConfigWrapper) {
        // 进行注册 broker
        List<RegisterBrokerResult> registerBrokerResultList = this.brokerOuterAPI.registerBrokerAll(
                this.brokerConfig.getBrokerClusterName(),
                this.getBrokerAddr(),
                this.brokerConfig.getBrokerName(),
                this.brokerConfig.getBrokerId(),
                this.getHAServerAddr(),
                topicConfigWrapper,
                this.filterServerManager.buildNewFilterServerList(),
                oneway,
                this.brokerConfig.getRegisterBrokerTimeoutMills(),
                this.brokerConfig.isCompressedRegister());
        // 返回的结果大于 0，则进行注册完成的后续处理
        if (registerBrokerResultList.size() > 0) {
            RegisterBrokerResult registerBrokerResult = registerBrokerResultList.get(0);
            if (registerBrokerResult != null) {
                // 更新 HA master 地址
                if (this.updateMasterHAServerAddrPeriodically && registerBrokerResult.getHaServerAddr() != null) {
                    this.messageStore.updateHaMasterAddress(registerBrokerResult.getHaServerAddr());
                }
                // 更新 slave 同步 master 的地址
                this.slaveSynchronize.setMasterAddr(registerBrokerResult.getMasterAddr());
                // 更新 topic 配置
                if (checkOrderConfig) {
                    this.getTopicConfigManager().updateOrderTopicConfig(registerBrokerResult.getKvTable());
                }
            }
        }
    }

    private boolean needRegister(final String clusterName,
                                 final String brokerAddr,
                                 final String brokerName,
                                 final long brokerId,
                                 final int timeoutMills) {

        TopicConfigSerializeWrapper topicConfigWrapper = this.getTopicConfigManager().buildTopicConfigSerializeWrapper();
        List<Boolean> changeList = brokerOuterAPI.needRegister(clusterName, brokerAddr, brokerName, brokerId, topicConfigWrapper, timeoutMills);
        boolean needRegister = false;
        for (Boolean changed : changeList) {
            if (changed) {
                needRegister = true;
                break;
            }
        }
        return needRegister;
    }

    public TopicConfigManager getTopicConfigManager() {
        return topicConfigManager;
    }

    public void setTopicConfigManager(TopicConfigManager topicConfigManager) {
        this.topicConfigManager = topicConfigManager;
    }

    public String getHAServerAddr() {
        return this.brokerConfig.getBrokerIP2() + ":" + this.messageStoreConfig.getHaListenPort();
    }

    public RebalanceLockManager getRebalanceLockManager() {
        return rebalanceLockManager;
    }

    public SlaveSynchronize getSlaveSynchronize() {
        return slaveSynchronize;
    }

    public ExecutorService getPullMessageExecutor() {
        return pullMessageExecutor;
    }

    public void setPullMessageExecutor(ExecutorService pullMessageExecutor) {
        this.pullMessageExecutor = pullMessageExecutor;
    }

    public BlockingQueue<Runnable> getSendThreadPoolQueue() {
        return sendThreadPoolQueue;
    }

    public FilterServerManager getFilterServerManager() {
        return filterServerManager;
    }

    public BrokerStatsManager getBrokerStatsManager() {
        return brokerStatsManager;
    }

    public List<SendMessageHook> getSendMessageHookList() {
        return sendMessageHookList;
    }

    public void registerSendMessageHook(final SendMessageHook hook) {
        this.sendMessageHookList.add(hook);
        log.info("register SendMessageHook Hook, {}", hook.hookName());
    }

    public List<ConsumeMessageHook> getConsumeMessageHookList() {
        return consumeMessageHookList;
    }

    public void registerConsumeMessageHook(final ConsumeMessageHook hook) {
        this.consumeMessageHookList.add(hook);
        log.info("register ConsumeMessageHook Hook, {}", hook.hookName());
    }

    public void registerServerRPCHook(RPCHook rpcHook) {
        getRemotingServer().registerRPCHook(rpcHook);
        this.fastRemotingServer.registerRPCHook(rpcHook);
    }

    public RemotingServer getRemotingServer() {
        return remotingServer;
    }

    public void setRemotingServer(RemotingServer remotingServer) {
        this.remotingServer = remotingServer;
    }

    public void registerClientRPCHook(RPCHook rpcHook) {
        this.getBrokerOuterAPI().registerRPCHook(rpcHook);
    }

    public BrokerOuterAPI getBrokerOuterAPI() {
        return brokerOuterAPI;
    }

    public InetSocketAddress getStoreHost() {
        return storeHost;
    }

    public void setStoreHost(InetSocketAddress storeHost) {
        this.storeHost = storeHost;
    }

    public Configuration getConfiguration() {
        return this.configuration;
    }

    public BlockingQueue<Runnable> getHeartbeatThreadPoolQueue() {
        return heartbeatThreadPoolQueue;
    }

    public TransactionalMessageCheckService getTransactionalMessageCheckService() {
        return transactionalMessageCheckService;
    }

    public void setTransactionalMessageCheckService(
            TransactionalMessageCheckService transactionalMessageCheckService) {
        this.transactionalMessageCheckService = transactionalMessageCheckService;
    }

    public TransactionalMessageService getTransactionalMessageService() {
        return transactionalMessageService;
    }

    public void setTransactionalMessageService(TransactionalMessageService transactionalMessageService) {
        this.transactionalMessageService = transactionalMessageService;
    }

    public AbstractTransactionalMessageCheckListener getTransactionalMessageCheckListener() {
        return transactionalMessageCheckListener;
    }

    public void setTransactionalMessageCheckListener(
            AbstractTransactionalMessageCheckListener transactionalMessageCheckListener) {
        this.transactionalMessageCheckListener = transactionalMessageCheckListener;
    }


    public BlockingQueue<Runnable> getEndTransactionThreadPoolQueue() {
        return endTransactionThreadPoolQueue;

    }

    public Map<Class, AccessValidator> getAccessValidatorMap() {
        return accessValidatorMap;
    }

    private void handleSlaveSynchronize(BrokerRole role) {
        if (role == BrokerRole.SLAVE) {
            if (null != slaveSyncFuture) {
                slaveSyncFuture.cancel(false);
            }
            this.slaveSynchronize.setMasterAddr(null);
            slaveSyncFuture = this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    try {
                        BrokerController.this.slaveSynchronize.syncAll();
                    } catch (Throwable e) {
                        log.error("ScheduledTask SlaveSynchronize syncAll error.", e);
                    }
                }
            }, 1000 * 3, 1000 * 10, TimeUnit.MILLISECONDS);
        } else {
            //handle the slave synchronise
            if (null != slaveSyncFuture) {
                slaveSyncFuture.cancel(false);
            }
            this.slaveSynchronize.setMasterAddr(null);
        }
    }

    public void changeToSlave(int brokerId) {
        log.info("Begin to change to slave brokerName={} brokerId={}", brokerConfig.getBrokerName(), brokerId);

        //change the role
        brokerConfig.setBrokerId(brokerId == 0 ? 1 : brokerId); //TO DO check
        messageStoreConfig.setBrokerRole(BrokerRole.SLAVE);

        //handle the scheduled service
        try {
            this.messageStore.handleScheduleMessageService(BrokerRole.SLAVE);
        } catch (Throwable t) {
            log.error("[MONITOR] handleScheduleMessageService failed when changing to slave", t);
        }

        //handle the transactional service
        try {
            this.shutdownProcessorByHa();
        } catch (Throwable t) {
            log.error("[MONITOR] shutdownProcessorByHa failed when changing to slave", t);
        }

        //handle the slave synchronise
        handleSlaveSynchronize(BrokerRole.SLAVE);

        try {
            this.registerBrokerAll(true, true, true);
        } catch (Throwable ignored) {

        }
        log.info("Finish to change to slave brokerName={} brokerId={}", brokerConfig.getBrokerName(), brokerId);
    }


    public void changeToMaster(BrokerRole role) {
        if (role == BrokerRole.SLAVE) {
            return;
        }
        log.info("Begin to change to master brokerName={}", brokerConfig.getBrokerName());

        //handle the slave synchronise
        handleSlaveSynchronize(role);

        //handle the scheduled service
        try {
            this.messageStore.handleScheduleMessageService(role);
        } catch (Throwable t) {
            log.error("[MONITOR] handleScheduleMessageService failed when changing to master", t);
        }

        //handle the transactional service
        try {
            this.startProcessorByHa(BrokerRole.SYNC_MASTER);
        } catch (Throwable t) {
            log.error("[MONITOR] startProcessorByHa failed when changing to master", t);
        }

        //if the operations above are totally successful, we change to master
        brokerConfig.setBrokerId(0); //TO DO check
        messageStoreConfig.setBrokerRole(role);

        try {
            this.registerBrokerAll(true, true, true);
        } catch (Throwable ignored) {

        }
        log.info("Finish to change to master brokerName={}", brokerConfig.getBrokerName());
    }

    private void startProcessorByHa(BrokerRole role) {
        if (BrokerRole.SLAVE != role) {
            if (this.transactionalMessageCheckService != null) {
                this.transactionalMessageCheckService.start();
            }
        }
    }

    private void shutdownProcessorByHa() {
        if (this.transactionalMessageCheckService != null) {
            this.transactionalMessageCheckService.shutdown(true);
        }
    }

    public ExecutorService getPutMessageFutureExecutor() {
        return putMessageFutureExecutor;
    }
}
