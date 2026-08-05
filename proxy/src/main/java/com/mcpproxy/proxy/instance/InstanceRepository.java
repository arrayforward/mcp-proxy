package com.mcpproxy.proxy.instance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * 实例注册表 JPA 仓储（t_cloud_phone_instance）。
 *
 * <p>功能：实例表的 CRUD 与常用查询。派生查询方法由 Spring Data 自动实现，无需手写 SQL。
 *
 * @author hubin
 * @since 2026-08-04
 */
public interface InstanceRepository extends JpaRepository<CloudPhoneInstance, String> {

    /** 某用户的全部实例（ListInstances 默认场景） */
    List<CloudPhoneInstance> findByUid(String uid);

    /** 某用户 + 指定实例 ID 集合（ListInstances 带 instance_ids 过滤场景） */
    List<CloudPhoneInstance> findByUidAndInstanceIdIn(String uid, Collection<String> instanceIds);

    /** 按状态扫描（HealthCheckService 找 NORMAL 实例做探活） */
    List<CloudPhoneInstance> findByStatus(int status);
}
