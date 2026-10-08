package com.mystaria.phantasmon_backend.hub;

/**
 * The blocks an anchor's build occupies (D-34, D-35), computed exactly like the client's {@code HubBuildLayout}: the
 * centre column is the block the anchor was posed from, the bottom layer the block of the player's feet (rounded up),
 * the hub's {@code sizeX × sizeY × sizeZ} box turned by the anchor's quarter-turns (+Z = the anchor's front). Inclusive
 * block coordinates.
 */
record HubBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

	static HubBox of(double originX, double originY, double originZ, int yaw, int sizeX, int sizeY, int sizeZ) {
		int centerX = (int) Math.floor(originX);
		int baseY = (int) Math.ceil(originY - 1.0E-4);
		int centerZ = (int) Math.floor(originZ);
		int turns = Math.floorMod(yaw, 360) / 90 % 4;
		int[] a = toWorld(centerX, baseY, centerZ, turns, sizeX, sizeZ, 0, 0, 0);
		int[] b = toWorld(centerX, baseY, centerZ, turns, sizeX, sizeZ, sizeX - 1, sizeY - 1, sizeZ - 1);
		return new HubBox(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]),
				Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2]));
	}

	static HubBox of(HubAnchor anchor, Hub hub) {
		return of(anchor.getOriginX(), anchor.getOriginY(), anchor.getOriginZ(), anchor.getYaw(), hub.getSizeX(),
				hub.getSizeY(), hub.getSizeZ());
	}

	private static int[] toWorld(int centerX, int baseY, int centerZ, int turns, int sizeX, int sizeZ, int x, int y, int z) {
		int dx = x - sizeX / 2;
		int dz = z - sizeZ / 2;
		return switch (turns) {
			case 1 -> new int[] { centerX - dz, baseY + y, centerZ + dx };
			case 2 -> new int[] { centerX - dx, baseY + y, centerZ - dz };
			case 3 -> new int[] { centerX + dz, baseY + y, centerZ - dx };
			default -> new int[] { centerX + dx, baseY + y, centerZ + dz };
		};
	}

	boolean intersects(HubBox other) {
		return minX <= other.maxX && other.minX <= maxX && minY <= other.maxY && other.minY <= maxY
				&& minZ <= other.maxZ && other.minZ <= maxZ;
	}
}
