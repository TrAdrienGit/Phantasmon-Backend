CREATE TABLE trades (
    uuid                UUID PRIMARY KEY,
    initiator_uuid      UUID NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    recipient_uuid      UUID NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    offered_pokemon     UUID NOT NULL REFERENCES pokemon(uuid) ON DELETE RESTRICT,
    requested_pokemon   UUID NOT NULL REFERENCES pokemon(uuid) ON DELETE RESTRICT,
    status              VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                         CHECK (status IN ('PENDING', 'ACCEPTED', 'CANCELLED', 'COMPLETED')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at         TIMESTAMPTZ,

    CONSTRAINT chk_trade_not_self CHECK (initiator_uuid <> recipient_uuid)
);

CREATE INDEX idx_trades_initiator ON trades(initiator_uuid);
CREATE INDEX idx_trades_recipient ON trades(recipient_uuid);
CREATE INDEX idx_trades_status ON trades(status) WHERE status = 'PENDING';
