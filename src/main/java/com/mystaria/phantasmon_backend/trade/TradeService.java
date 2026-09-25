package com.mystaria.phantasmon_backend.trade;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mystaria.phantasmon_backend.common.ApiException;
import com.mystaria.phantasmon_backend.pokemon.Pokemon;
import com.mystaria.phantasmon_backend.pokemon.PokemonRepository;
import com.mystaria.phantasmon_backend.pokemon.PokemonService;
import com.mystaria.phantasmon_backend.websocket.SessionRegistry;
import com.mystaria.phantasmon_backend.websocket.WsMessage;

import lombok.extern.slf4j.Slf4j;

/**
 * Proposal/acceptance/cancellation flow for Pokémon trades (CAD Partie 3 §D).
 * Acceptance is a single atomic transaction: either both {@code owner_uuid}
 * columns change together, or nothing changes at all.
 */
@Service
@Slf4j
public class TradeService {

	private final TradeRepository tradeRepository;
	private final PokemonRepository pokemonRepository;
	private final PokemonService pokemonService;
	private final SessionRegistry sessionRegistry;

	public TradeService(TradeRepository tradeRepository, PokemonRepository pokemonRepository,
			PokemonService pokemonService, SessionRegistry sessionRegistry) {
		this.tradeRepository = tradeRepository;
		this.pokemonRepository = pokemonRepository;
		this.pokemonService = pokemonService;
		this.sessionRegistry = sessionRegistry;
	}

	@Transactional
	public TradeResponse propose(UUID initiatorUuid, ProposeTradeRequest request) {
		if (initiatorUuid.equals(request.recipientUuid())) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_TRADE_SELF", Map.of());
		}

		Pokemon offered = findPokemon(request.offeredPokemonUuid());
		if (!offered.getOwnerUuid().equals(initiatorUuid)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ERROR_OWNERSHIP_MISMATCH", Map.of("uuid", offered.getUuid()));
		}

		Pokemon requested = findPokemon(request.requestedPokemonUuid());
		if (!requested.getOwnerUuid().equals(request.recipientUuid())) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_TRADE_INVALID_RECIPIENT_POKEMON",
					Map.of("uuid", requested.getUuid()));
		}

		Trade trade = new Trade(UUID.randomUUID(), initiatorUuid, request.recipientUuid(),
				offered.getUuid(), requested.getUuid());
		tradeRepository.save(trade);

		log.info("Trade {} proposed: {} offers {} for {}'s {}", trade.getUuid(), initiatorUuid,
				offered.getUuid(), request.recipientUuid(), requested.getUuid());

		sessionRegistry.send(request.recipientUuid(), WsMessage.of("TradeProposed", Map.of(
				"trade_uuid", trade.getUuid(), "initiator_uuid", initiatorUuid,
				"offered_pokemon", offered.getUuid(), "requested_pokemon", requested.getUuid())));

		return TradeResponse.from(trade);
	}

	/**
	 * {@code noRollbackFor}: per CAD Partie 3 §D.2, a detected ownership drift
	 * must still commit the trade's {@code CANCELLED} status (only the
	 * ownership *swap* is what stays all-or-nothing) even though this method
	 * then throws — Spring's default is to roll back the whole transaction on
	 * any unchecked exception, which would silently undo that status write.
	 */
	@Transactional(noRollbackFor = ApiException.class)
	public TradeResponse accept(UUID callerUuid, UUID tradeUuid) {
		Trade trade = findTrade(tradeUuid);
		if (!trade.getRecipientUuid().equals(callerUuid)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ERROR_OWNERSHIP_MISMATCH", Map.of("uuid", tradeUuid));
		}
		requirePending(trade);

		Pokemon offered = findPokemon(trade.getOfferedPokemon());
		Pokemon requested = findPokemon(trade.getRequestedPokemon());

		if (!offered.getOwnerUuid().equals(trade.getInitiatorUuid()) || !requested.getOwnerUuid().equals(trade.getRecipientUuid())) {
			trade.setStatus(TradeStatus.CANCELLED);
			trade.setResolvedAt(Instant.now());
			tradeRepository.save(trade);
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_TRADE_OWNERSHIP_CHANGED", Map.of("uuid", tradeUuid));
		}

		pokemonService.transferOwnership(offered.getUuid(), trade.getRecipientUuid());
		pokemonService.transferOwnership(requested.getUuid(), trade.getInitiatorUuid());

		trade.setStatus(TradeStatus.COMPLETED);
		trade.setResolvedAt(Instant.now());
		tradeRepository.save(trade);

		log.info("Trade {} completed", tradeUuid);

		sessionRegistry.send(trade.getInitiatorUuid(), WsMessage.of("TradeAccepted", Map.of("trade_uuid", tradeUuid)));
		sessionRegistry.send(trade.getRecipientUuid(), WsMessage.of("TradeAccepted", Map.of("trade_uuid", tradeUuid)));

		return TradeResponse.from(trade);
	}

	@Transactional
	public TradeResponse cancel(UUID callerUuid, UUID tradeUuid) {
		Trade trade = findTrade(tradeUuid);
		if (!trade.getInitiatorUuid().equals(callerUuid) && !trade.getRecipientUuid().equals(callerUuid)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ERROR_OWNERSHIP_MISMATCH", Map.of("uuid", tradeUuid));
		}
		requirePending(trade);

		trade.setStatus(TradeStatus.CANCELLED);
		trade.setResolvedAt(Instant.now());
		tradeRepository.save(trade);

		log.info("Trade {} cancelled by {}", tradeUuid, callerUuid);

		sessionRegistry.send(trade.getInitiatorUuid(), WsMessage.of("TradeCancelled", Map.of("trade_uuid", tradeUuid)));
		sessionRegistry.send(trade.getRecipientUuid(), WsMessage.of("TradeCancelled", Map.of("trade_uuid", tradeUuid)));

		return TradeResponse.from(trade);
	}

	@Transactional(readOnly = true)
	public TradeResponse get(UUID callerUuid, UUID tradeUuid) {
		Trade trade = findTrade(tradeUuid);
		if (!trade.getInitiatorUuid().equals(callerUuid) && !trade.getRecipientUuid().equals(callerUuid)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ERROR_OWNERSHIP_MISMATCH", Map.of("uuid", tradeUuid));
		}
		return TradeResponse.from(trade);
	}

	@Transactional(readOnly = true)
	public List<TradeResponse> listForPlayer(UUID playerUuid) {
		return tradeRepository.findByInitiatorUuidOrRecipientUuid(playerUuid, playerUuid).stream()
				.map(TradeResponse::from).toList();
	}

	private Trade findTrade(UUID tradeUuid) {
		return tradeRepository.findById(tradeUuid)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ERROR_TRADE_NOT_FOUND", Map.of("uuid", tradeUuid)));
	}

	private Pokemon findPokemon(UUID pokemonUuid) {
		return pokemonRepository.findById(pokemonUuid)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ERROR_POKEMON_NOT_FOUND", Map.of("uuid", pokemonUuid)));
	}

	private static void requirePending(Trade trade) {
		if (trade.getStatus() != TradeStatus.PENDING) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_TRADE_INVALID_STATE",
					Map.of("uuid", trade.getUuid(), "status", trade.getStatus().name()));
		}
	}
}
