package com.mystaria.phantasmon_backend.events.tournament;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tournaments' public page (plan-tournois.md, phase T5): no account, read only, for Discord or a stream —
 * {@code /t} lists the tournaments, {@code /t/<name>} shows one (players, rounds, standings or bracket), kept up to
 * date by the page itself. The players' UUIDs are left out.
 */
@RestController
public class PublicTournamentController {

	private final TournamentService service;

	public PublicTournamentController(TournamentService service) {
		this.service = service;
	}

	@GetMapping("/public/tournaments")
	public List<TournamentService.View> list() {
		return service.list().stream().map(PublicTournamentController::anonymous).toList();
	}

	@GetMapping("/public/tournaments/{name}")
	public TournamentService.View get(@PathVariable String name) {
		return anonymous(service.get(name));
	}

	@GetMapping({ "/t", "/t/", "/t/{name}" })
	public ResponseEntity<String> page() throws IOException {
		try (InputStream in = new ClassPathResource("public/tournament.html").getInputStream()) {
			return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).cacheControl(CacheControl.noCache())
					.body(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	private static TournamentService.View anonymous(TournamentService.View view) {
		return new TournamentService.View(view.name(), view.kind(), view.bestOf(), view.formatId(), view.formatName(),
				view.status(), view.startsAt(), view.registrationOpensAt(), view.registrationClosesAt(), view.registrationOpen(),
				view.plannedRounds(), view.currentRound(), view.players().stream()
						.map(p -> new TournamentService.PlayerView(null, p.username(), p.checkedIn(), p.dropped())).toList(),
				view.matches(), view.standings());
	}
}
