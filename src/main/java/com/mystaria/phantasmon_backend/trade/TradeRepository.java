package com.mystaria.phantasmon_backend.trade;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeRepository extends JpaRepository<Trade, UUID> {

	List<Trade> findByInitiatorUuidOrRecipientUuid(UUID initiatorUuid, UUID recipientUuid);
}
