package com.mcpproxy.proxy.instance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface InstanceRepository extends JpaRepository<CloudPhoneInstance, String> {

    List<CloudPhoneInstance> findByUid(String uid);

    List<CloudPhoneInstance> findByUidAndInstanceIdIn(String uid, Collection<String> instanceIds);

    List<CloudPhoneInstance> findByStatus(int status);
}
