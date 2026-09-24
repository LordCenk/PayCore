package com.paycore.audit;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByEntityIdInOrderByIdAsc(Collection<String> entityIds);

    List<AuditLog> findByEntityIdAndActionOrderByIdAsc(String entityId, String action);
}
