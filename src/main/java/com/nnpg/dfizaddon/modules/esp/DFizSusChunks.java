        Color rim = new Color(255, 100, 100, Math.min(255, a + 80));

        Set<ChunkPos> plates = new HashSet<>();
        for (ChunkPos cp : susChunks) {
            plates.add(cp);
            plates.add(new ChunkPos(cp.x + 1, cp.z));
            plates.add(new ChunkPos(cp.x - 1, cp.z));
            plates.add(new ChunkPos(cp.x, cp.z + 1));
            plates.add(new ChunkPos(cp.x, cp.z - 1));
            plates.add(new ChunkPos(cp.x + 1, cp.z + 1));
            plates.add(new ChunkPos(cp.x - 1, cp.z - 1));
        }

        for (ChunkPos chunkPos : plates) {
            event.renderer.box(chunkPos.getStartX() - 0.1, PLATE_Y - 0.02, chunkPos.getStartZ() - 0.1,
                chunkPos.getStartX() + 16.1, PLATE_Y + 0.12, chunkPos.getStartZ() + 16.1,
                rim, rim, ShapeMode.Sides, 0);
        }

        for (ChunkPos chunkPos : plates) {
            event.renderer.box(chunkPos.getStartX() - 0.05, PLATE_Y, chunkPos.getStartZ() - 0.05,
                chunkPos.getStartX() + 16.05, PLATE_Y + 0.1, chunkPos.getStartZ() + 16.05,
                fill, fill, ShapeMode.Sides, 0);
        }

        if (showExposedAmethyst.get()) {
            Color orange = new Color(255, 165, 0, Math.max(0, Math.min(255, alpha.get())));
            for (BlockPos bp : exposedPositions) {
                event.renderer.box(bp.getX(), bp.getY(), bp.getZ(), bp.getX() + 1, bp.getY() + 1, bp.getZ() + 1,
                    orange, orange, ShapeMode.Sides, 0);
            }
        }
    }
}
