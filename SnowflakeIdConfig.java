package com.xxx.config;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.montnets.aim.h5.util.RedisUtil;
import com.montnets.common.exception.BaseHandlerException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Copyright (C), 1998-2021, Shenzhen Montnets Technology Co., Ltd
 * 雪花算法配置类
 *
 * @author gaojs
 * @date 2025/2/27 09:50
 * @since 1.0.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnowflakeIdConfig {
	
	
    /**
	 * 将如下服务替换为你自己的服务名（spring.application.name）
     * 服务列表：所有需要生成雪花算法的服务都列入其中
     */
    private final List<String> SERVICE_NAME_LIST = Arrays.asList(
            "H5Service",
            // DRC 服务
            "DataService", "ReportService",
            // EC 服务
            "AimService", "SystemService", "UserService",
            // MC 服务
            "SyncService", "ScodeService", "ApiService","ParamService",
            // OS 服务
            "TemplateService", "AuthService", "SmsService", "AgentService", "AuditService", "OsSystemService"
    );

    private final RedisUtil redisUtil;
    private final ReleaseCombinations releaseCombinations;
    private final NacosServiceManager nacosServiceManager;
    private final NacosDiscoveryProperties nacosDiscoveryProperties;

    @Value("${spring.cloud.nacos.config.shared-configs[0].group:DEFAULT_GROUP}")
    private String nacosConfigGroup;

    /**
     * serverServiceNode 范围为 0-127
     */
    private static final int MAX_WORKER_ID = 127;
    /**
     * serverRoomNode 范围为 0-7
     */
    private static final int MAX_DATA_CENTER_ID = 7;
    private static final Set<String> USED_COMBINATIONS = ConcurrentHashMap.newKeySet();

    /**
     * 1.获取nacos元数据中所有的serverServiceNode+serverRoomNode的已分配的组合
     * 2.循环遍历所有可能的serverRoomNode和serverServiceNode组合，如果组合未使用过，则取出来分配给当前服务
     * 3.将当前分配的组合保存到元数据内
     * 4.将当前服务分配的serverServiceNode、serverRoomNode写入环境变量，供雪花算法使用
     */
    @PostConstruct
    public void initSnowflakeIdConfig() {
        log.info("初始化雪花算法配置开始，nacos组名：{}", nacosConfigGroup);
        try {
            int[] ids = getAvailableCombination();
            int serverServiceNode = ids[0];
            int serverRoomNode = ids[1];
            updateMetadataWithRetry(serverServiceNode, serverRoomNode);
            // 设置环境变量
            System.setProperty("server_service_node", String.valueOf(serverServiceNode));
            System.setProperty("server_room_node", String.valueOf(serverRoomNode));
            log.info("初始化雪花算法配置结束，workerId：{}，dataCenterId：{}", serverServiceNode, serverRoomNode);
        } catch (Exception e) {
            log.error("初始化雪花算法配置异常: {}", e.getMessage());
            throw new BaseHandlerException(500, "初始化雪花算法配置异常：" + e.getMessage());
        }
    }

    /**
     * 获取可用的组合（serverServiceNode + serverRoomNode），找到可用组合后将该组合在redis中加锁
     */
    private int[] getAvailableCombination() {
        Set<String> usedCombinations = getAllUsedCombinations();

        // 从小到大，遍历所有可能的serverRoomNode和serverServiceNode组合
        // serverServiceNode到大最大值后，serverRoomNode自增，serverServiceNode继续从0开始
        for (int serverRoomNode = 0; serverRoomNode <= MAX_DATA_CENTER_ID; serverRoomNode++) {
            for (int serverServiceNode = 0; serverServiceNode <= MAX_WORKER_ID; serverServiceNode++) {
                String combinationKey = serverRoomNode + "-" + serverServiceNode;
                if (!usedCombinations.contains(combinationKey)) {
                    // nacos元数据更新存在延迟，这里用redis加锁避免重复分配，后续检查nacos更新完成后再释放锁
                    boolean isExists = redisUtil.setIfAbsent("snowflake_worker_" + combinationKey, "1", 10, TimeUnit.MINUTES);
                    if (!isExists) {
                        log.info("初始化雪花算法配置-{}组合在redis已存在，继续遍历.", combinationKey);
                        continue;
                    }
                    return new int[]{serverServiceNode, serverRoomNode};
                }
            }
        }

        throw new BaseHandlerException(500, "雪花算法分配失败，无可用的serverRoomNode和serverServiceNode组合");
    }

    /**
     * 查询所有已分配的serverRoomNode-serverServiceNode组合
     */
    private Set<String> getAllUsedCombinations() {
        try {
            Set<String> combinations = new HashSet<>();

            for (String serviceName : SERVICE_NAME_LIST) {
                releaseCombinations.queryAndAddCombinationList(serviceName, combinations, nacosServiceManager, nacosConfigGroup);
            }
            log.info("初始化雪花算法配置-查询Nacos实例成功，已分配的组合：{}", combinations);
            return combinations;
        } catch (NacosException e) {
            log.error("初始化雪花算法配置-查询Nacos实例异常: {}", e.getMessage());
            throw new BaseHandlerException(500, "查询Nacos实例异常: " + e.getMessage());
        }
    }

    /**
     * 更新元数据到Nacos
     */
    private void updateMetadataWithRetry(int serverServiceNode, int serverRoomNode) {
        String dwKey = serverRoomNode + "-" + serverServiceNode;
        int retryCount = 3;
        while (retryCount > 0) {
            try {
                Map<String, String> metadata = nacosDiscoveryProperties.getMetadata();
                metadata.put("serverServiceNode", String.valueOf(serverServiceNode));
                metadata.put("serverRoomNode", String.valueOf(serverRoomNode));
                // 设置元数据到待注册的实例属性中（@PostConstruct注解内的方法执行完成才会将实例注册到nacos）
                nacosDiscoveryProperties.setMetadata(metadata);
                USED_COMBINATIONS.add(dwKey);
                break;
            } catch (Exception e) {
                retryCount--;
                if (retryCount == 0) {
                    throw new BaseHandlerException(500, "更新Nacos元数据失败: " + e.getMessage());
                }
            }
        }
        Set<String> combinations = new HashSet<>();
        // 异步释放redis锁
        releaseCombinations.asyncRelease(combinations, dwKey, redisUtil, nacosServiceManager, nacosConfigGroup);
        log.info("初始化雪花算法配置-nacos元数据更新结束：{}", combinations);
    }

    @Slf4j
    @Component
    public static class ReleaseCombinations {

        @Value("${spring.application.name}")
        private String currentServiceName;

        /**
         * 异步查询nacos元数据，确认元数据更新完成时，休眠10秒后释放redis锁
         */
        @Async
        public void asyncRelease(Set<String> combinations, String dwCombination, RedisUtil redisUtil, NacosServiceManager nacosServiceManager, String nacosConfigGroup) {
            try {
                int count = 600;
                while (count > 0) {
                    count--;
                    queryAndAddCombinationList(currentServiceName, combinations, nacosServiceManager, nacosConfigGroup);
                    log.info("{}-nacos元数据注册完成的雪花组合列表：{}", currentServiceName, combinations);
                    Thread.sleep(1000);
                    if (combinations.contains(dwCombination)) {
                        // 雪花组合元数据更新完成，等待1分钟再释放redis锁（预防nacos节点同步延迟之类的问题）
                        Thread.sleep(60000);
                        redisUtil.remove("snowflake_worker_" + dwCombination);
                        log.info("{}-nacos雪花组合元数据更新完成，释放redis锁：{}", currentServiceName, dwCombination);
                        break;
                    }
                }
                if (count <= 0) {
                    // 出现未知问题导致超时未释放则打日志后等待redis自动过期
                    log.info("{}-雪花组合释放超时：{}", currentServiceName, dwCombination);
                }
            } catch (NacosException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.error("AuditService-雪花组合释放锁异常：{}", e.getMessage(), e);
            }
        }

        /**
         * 根据服务名和组名查询所有已分配的serverRoomNode-serverServiceNode组合并添加到combinations集合中
         *
         * @author  gaojs
         * @date    2025/3/28 14:59
         * @param   serviceName /
         * @param   combinations /
         * @param   nacosServiceManager /
         * @param   nacosConfigGroup /
         */
        public void queryAndAddCombinationList(String serviceName, Set<String> combinations, NacosServiceManager nacosServiceManager, String nacosConfigGroup) throws NacosException {
            NamingService namingService = nacosServiceManager.getNamingService();
            List<Instance> instances = namingService.getAllInstances(serviceName, nacosConfigGroup, false);
            String serviceListStr = instances.stream().map(x -> x.getIp() + ":" + x.getPort()).collect(Collectors.joining("、"));
            log.info("{}-查询nacos完成，已注册实例：{}", serviceName, serviceListStr);
            for (Instance instance : instances) {
                String dcId = instance.getMetadata().get("serverRoomNode");
                String serverServiceNode = instance.getMetadata().get("serverServiceNode");
                if (dcId == null || serverServiceNode == null) {
                    log.info("{}-{}-{}-nacos已注册实例雪花组合为空！serverRoomNode：{}，serverServiceNode：{}"
                            , serviceName, instance.getIp(), instance.getPort(), dcId, serverServiceNode);
                    continue;
                }
                String dsStr = dcId + "-" + serverServiceNode;
                combinations.add(dsStr);
            }
        }
    }
}