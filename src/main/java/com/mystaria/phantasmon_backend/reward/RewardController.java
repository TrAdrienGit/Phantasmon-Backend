package com.mystaria.phantasmon_backend.reward;

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

/** Rewards (TODO-37): a player reads their collection; admins give and take back. */
@RestController
public class RewardController {

	private final RewardService rewards;
	private final AdminService adminService;

	public RewardController(RewardService rewards, AdminService adminService) {
		this.rewards = rewards;
		this.adminService = adminService;
	}

	@GetMapping("/rewards/mine")
	public List<RewardService.RewardView> mine(Authentication authentication) {
		return rewards.mine(caller(authentication));
	}

	@GetMapping("/admin/players/{name}/rewards")
	public RewardService.PlayerRewards ofPlayer(@PathVariable String name, Authentication authentication) {
		adminService.requireAdmin(caller(authentication));
		return rewards.ofPlayer(name);
	}

	public record GrantRequest(String kind, String value, String species, String note) {
	}

	@PostMapping("/admin/players/{name}/rewards")
	public ResponseEntity<RewardService.RewardView> grant(@PathVariable String name, @RequestBody GrantRequest request,
			Authentication authentication) {
		UUID admin = caller(authentication);
		adminService.requireAdmin(admin);
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(rewards.grant(admin, name, request.kind(), request.value(), request.species(), request.note()));
	}

	@DeleteMapping("/admin/rewards/{uuid}")
	public ResponseEntity<Void> revoke(@PathVariable UUID uuid, Authentication authentication) {
		UUID admin = caller(authentication);
		adminService.requireAdmin(admin);
		rewards.revoke(admin, uuid);
		return ResponseEntity.noContent().build();
	}

	private static UUID caller(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}
}
