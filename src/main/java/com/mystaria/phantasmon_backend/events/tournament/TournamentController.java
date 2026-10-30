package com.mystaria.phantasmon_backend.events.tournament;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.mystaria.phantasmon_backend.admin.AdminService;

/** Tournaments (plan-tournois.md): read and joined by every player, run by admins. */
@RestController
public class TournamentController {

	private final TournamentService service;
	private final TournamentRunService run;
	private final AdminService adminService;

	public TournamentController(TournamentService service, TournamentRunService run, AdminService adminService) {
		this.service = service;
		this.run = run;
		this.adminService = adminService;
	}

	/** {@code POST /admin/tournaments/{name}/result}: {@code username} wins their match, or loses it by forfeit. */
	public record ResultRequest(String username, Boolean forfeit) {
	}

	public record RoundsRequest(Integer delta) {
	}

	public record PlayerRequest(String username) {
	}

	@GetMapping("/tournaments")
	public List<TournamentService.View> list() {
		return service.list();
	}

	@GetMapping("/tournaments/{name}")
	public TournamentService.View get(@PathVariable String name) {
		return service.get(name);
	}

	@PostMapping("/tournaments/{name}/register")
	public TournamentService.View register(@PathVariable String name, Authentication authentication) {
		return service.register(caller(authentication), name);
	}

	@DeleteMapping("/tournaments/{name}/register")
	public TournamentService.View unregister(@PathVariable String name, Authentication authentication) {
		return service.unregister(caller(authentication), name);
	}

	@PostMapping("/tournaments/{name}/check-in")
	public TournamentService.View checkIn(@PathVariable String name, Authentication authentication) {
		return service.checkIn(caller(authentication), name);
	}

	@PostMapping("/admin/tournaments")
	public ResponseEntity<TournamentService.View> create(@RequestBody TournamentService.CreateRequest request,
			Authentication authentication) {
		return ResponseEntity.status(HttpStatus.CREATED).body(service.create(admin(authentication), request));
	}

	@PostMapping("/admin/tournaments/{name}/schedule")
	public TournamentService.View schedule(@PathVariable String name, @RequestBody TournamentService.ScheduleRequest request,
			Authentication authentication) {
		admin(authentication);
		return service.schedule(name, request);
	}

	@PostMapping("/admin/tournaments/{name}/players")
	public TournamentService.View addPlayer(@PathVariable String name, @RequestBody PlayerRequest request,
			Authentication authentication) {
		return service.adminAdd(admin(authentication), name, request.username());
	}

	@DeleteMapping("/admin/tournaments/{name}/players/{username}")
	public TournamentService.View removePlayer(@PathVariable String name, @PathVariable String username,
			Authentication authentication) {
		return service.adminRemove(admin(authentication), name, username);
	}

	@PostMapping("/admin/tournaments/{name}/check-in")
	public TournamentService.View openCheckIn(@PathVariable String name, Authentication authentication) {
		admin(authentication);
		return service.openCheckIn(name);
	}

	@PostMapping("/admin/tournaments/{name}/cancel")
	public TournamentService.View cancel(@PathVariable String name, Authentication authentication) {
		return service.cancel(admin(authentication), name);
	}

	@PostMapping("/admin/tournaments/{name}/start")
	public TournamentService.View start(@PathVariable String name, Authentication authentication) {
		return run.start(admin(authentication), name);
	}

	@PostMapping("/admin/tournaments/{name}/next-round")
	public TournamentService.View nextRound(@PathVariable String name, Authentication authentication) {
		return run.nextRound(admin(authentication), name);
	}

	@PostMapping("/admin/tournaments/{name}/rounds")
	public TournamentService.View rounds(@PathVariable String name, @RequestBody RoundsRequest request, Authentication authentication) {
		admin(authentication);
		return run.changeRounds(name, request.delta() == null ? 0 : request.delta());
	}

	@PostMapping("/admin/tournaments/{name}/result")
	public TournamentService.View result(@PathVariable String name, @RequestBody ResultRequest request, Authentication authentication) {
		return run.setResult(admin(authentication), name, request.username(), Boolean.TRUE.equals(request.forfeit()));
	}

	@PostMapping("/admin/tournaments/{name}/reinvite")
	public TournamentService.View reinvite(@PathVariable String name, @RequestBody PlayerRequest request, Authentication authentication) {
		admin(authentication);
		return run.reinvite(name, request.username());
	}

	@PostMapping("/admin/tournaments/{name}/finish")
	public TournamentService.View finish(@PathVariable String name, Authentication authentication) {
		return run.finish(admin(authentication), name);
	}

	@DeleteMapping("/admin/tournaments/{name}")
	public ResponseEntity<Void> purge(@PathVariable String name, Authentication authentication) {
		service.purge(admin(authentication), name);
		return ResponseEntity.noContent().build();
	}

	private UUID admin(Authentication authentication) {
		UUID admin = caller(authentication);
		adminService.requireAdmin(admin);
		return admin;
	}

	private static UUID caller(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}
}
