package com.amanahconnect.complaint;

import com.amanahconnect.tenant.TenantRepository;
import java.util.List;
import java.util.UUID;

public interface ComplaintCommentRepository extends TenantRepository<ComplaintComment, UUID> {

    List<ComplaintComment> findByCommunityIdAndComplaintIdOrderByCreatedAtAsc(UUID communityId, UUID complaintId);
}
