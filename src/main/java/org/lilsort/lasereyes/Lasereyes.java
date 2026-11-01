package org.lilsort.lasereyes;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class Lasereyes implements ModInitializer {

    // Списки активных режимов для игроков
    private static final Set<UUID> activeLaserPlayers = new HashSet<>();
    private static final Set<UUID> activeFlyPlayers = new HashSet<>();
    private static final Set<UUID> activeGrabPlayers = new HashSet<>();

    private static final Map<UUID, Integer> chargeTime = new HashMap<>();
    private static final Map<UUID, Integer> buttonHoldTimer = new HashMap<>();
    private static final Map<UUID, Boolean> lastSprintingState = new HashMap<>();

    // Словари для системы захвата игроков
    private static final Map<UUID, UUID> adminToGrabbed = new HashMap<>();
    private static final Map<UUID, UUID> grabbedToAdmin = new HashMap<>();

    // Карта для хранения времени (тика), когда админ схватил игрока (для предотвращения двойного клика)
    private static final Map<UUID, Integer> lastGrabTick = new HashMap<>();

    // Карта для отслеживания высоты падения в прошлом тике
    private static final Map<UUID, Float> prevTickFallDistance = new HashMap<>();

    private static final int MAX_CHARGE = 20;
    private static final double MAX_DISTANCE = 64.0;
    private static final double PARTICLE_STEP_BEAM = 0.15;
    private static final double PARTICLE_STEP_GLOW = 0.05;
    private static final float DEFAULT_FLY_SPEED = 0.05f;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerCommands(dispatcher);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {

            // 1. Логика полёта Супермена и эффектного приземления
            for (UUID uuid : activeFlyPlayers) {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
                if (player != null) {
                    float currentFallDistance = player.fallDistance;
                    float previousFallDistance = prevTickFallDistance.getOrDefault(uuid, 0.0f);

                    // Эффектное приземление: коснулся земли и падал минимум 16 блоков
                    if (player.isOnGround() && previousFallDistance >= 16.0f && !player.isSpectator()) {
                        spawnSuperheroLandingEffect(player, (ServerWorld) player.getWorld());
                    }

                    // Обновляем состояние падения для следующего тика
                    prevTickFallDistance.put(uuid, currentFallDistance);

                    boolean wasSprinting = lastSprintingState.getOrDefault(uuid, false);
                    boolean isSprinting = player.isSprinting();

                    // Эффект сверхзвука: работает только если НЕ в режиме наблюдателя
                    if (player.getAbilities().flying && isSprinting && !player.isSpectator()) {
                        if (!wasSprinting) {
                            spawnSonicBoomEffect(player, (ServerWorld) player.getWorld());
                        }
                        player.setPose(EntityPose.SWIMMING);
                    }
                    lastSprintingState.put(uuid, isSprinting);
                }
            }

            // 2. Логика удушающего захвата
            Set<UUID> grabToRemove = new HashSet<>();
            for (Map.Entry<UUID, UUID> entry : adminToGrabbed.entrySet()) {
                ServerPlayerEntity admin = server.getPlayerManager().getPlayer(entry.getKey());
                ServerPlayerEntity target = server.getPlayerManager().getPlayer(entry.getValue());

                // Если кто-то вышел с сервера, умер или админ выключил режим — прерываем захват
                if (admin == null || target == null || !admin.isAlive() || !target.isAlive() || !activeGrabPlayers.contains(admin.getUuid())) {
                    grabToRemove.add(entry.getKey());
                    continue;
                }

                // Игрок в креативе нажимает Shift чтобы вырваться
                if (target.isSneaking() && target.isCreative()) {
                    grabToRemove.add(admin.getUuid());
                    admin.sendMessage(Text.literal("§cИгрок вырвался из захвата!"), true);
                    target.sendMessage(Text.literal("§aВы вырвались из захвата!"), true);
                    continue;
                }

                // Высчитываем точку захвата (перед админом, чуть ниже глаз)
                Vec3d adminEye = admin.getEyePos();
                Vec3d look = admin.getRotationVec(1.0f);
                Vec3d desiredPos = adminEye.add(look.multiply(1.5)).subtract(0, 1.2, 0);

                // Жестко телепортируем и копируем скорость админа для максимальной плавности
                target.requestTeleport(desiredPos.x, desiredPos.y, desiredPos.z);
                target.setVelocity(admin.getVelocity());
                target.velocityModified = true;
                target.fallDistance = 0.0f; // Защита от урона от падения для жертвы пока держим
            }

            // Очистка словарей от разорванных захватов
            for (UUID adminId : grabToRemove) {
                UUID targetId = adminToGrabbed.remove(adminId);
                if (targetId != null) grabbedToAdmin.remove(targetId);
            }

            // 3. Логика зарядки и стрельбы лазером
            for (UUID uuid : activeLaserPlayers) {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
                if (player == null) continue;

                int holdTicks = buttonHoldTimer.getOrDefault(uuid, 0);
                int currentCharge = chargeTime.getOrDefault(uuid, 0);

                if (holdTicks > 0) {
                    if (currentCharge < MAX_CHARGE) {
                        currentCharge++;
                        chargeTime.put(uuid, currentCharge);
                        spawnEyeGlow(player, (ServerWorld) player.getWorld());

                        if (currentCharge == 1) {
                            player.getWorld().playSound(null, player.getBlockPos(), SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1.0f, 2.0f);
                        }
                    } else {
                        fireFullLaser(player, (ServerWorld) player.getWorld());

                        if (server.getTicks() % 10 == 0) {
                            player.getWorld().playSound(null, player.getBlockPos(), SoundEvents.ENTITY_GUARDIAN_ATTACK, SoundCategory.PLAYERS, 0.5f, 2.0f);
                        }
                    }
                    buttonHoldTimer.put(uuid, holdTicks - 1);
                } else {
                    if (currentCharge > 0) {
                        chargeTime.put(uuid, 0);
                    }
                }
            }
        });


        // ---------------- ИВЕНТЫ ВЗАИМОДЕЙСТВИЯ ----------------

        // Захват игрока (ПКМ по сущности)
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClient() && hand == Hand.MAIN_HAND) {
                ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;

                // Отпускаем игрока по ПКМ с кулдауном 10 тиков (0.5 сек) от момента захвата (защита от двойного клика)
                if (adminToGrabbed.containsKey(serverPlayer.getUuid())) {
                    if (serverPlayer.server.getTicks() - lastGrabTick.getOrDefault(serverPlayer.getUuid(), 0) > 10) {
                        releaseGrabbedPlayer(serverPlayer);
                    }
                    return ActionResult.SUCCESS;
                }

                // Хватаем игрока
                if (activeGrabPlayers.contains(serverPlayer.getUuid()) && entity instanceof ServerPlayerEntity targetPlayer) {
                    adminToGrabbed.put(serverPlayer.getUuid(), targetPlayer.getUuid());
                    grabbedToAdmin.put(targetPlayer.getUuid(), serverPlayer.getUuid());
                    lastGrabTick.put(serverPlayer.getUuid(), serverPlayer.server.getTicks()); // Запоминаем тик захвата

                    serverPlayer.sendMessage(Text.literal("§a[LaserEyes] Вы схватили " + targetPlayer.getName().getString() + " за горло! (ПКМ чтобы отпустить)"), true);
                    return ActionResult.SUCCESS;
                }
            }
            return ActionResult.PASS;
        });

        // Запрет использования блоков для схваченного игрока
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (grabbedToAdmin.containsKey(player.getUuid())) return ActionResult.FAIL;

            if (!world.isClient() && hand == Hand.MAIN_HAND) {
                ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;

                if (adminToGrabbed.containsKey(serverPlayer.getUuid())) {
                    if (serverPlayer.server.getTicks() - lastGrabTick.getOrDefault(serverPlayer.getUuid(), 0) > 10) {
                        releaseGrabbedPlayer(serverPlayer);
                    }
                    return ActionResult.FAIL;
                }

                if (activeLaserPlayers.contains(serverPlayer.getUuid())) {
                    buttonHoldTimer.put(serverPlayer.getUuid(), 5);
                    return ActionResult.FAIL;
                }
            }
            return ActionResult.PASS;
        });

        // Запрет использования предметов для схваченного
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (grabbedToAdmin.containsKey(player.getUuid())) return TypedActionResult.fail(player.getStackInHand(hand));

            if (!world.isClient() && hand == Hand.MAIN_HAND) {
                ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;

                if (adminToGrabbed.containsKey(serverPlayer.getUuid())) {
                    if (serverPlayer.server.getTicks() - lastGrabTick.getOrDefault(serverPlayer.getUuid(), 0) > 10) {
                        releaseGrabbedPlayer(serverPlayer);
                    }
                    return TypedActionResult.fail(player.getStackInHand(hand));
                }

                if (activeLaserPlayers.contains(serverPlayer.getUuid())) {
                    buttonHoldTimer.put(serverPlayer.getUuid(), 5);
                    return TypedActionResult.fail(player.getStackInHand(hand));
                }
            }
            return TypedActionResult.pass(player.getStackInHand(hand));
        });

        // Блок атаки и разрушения для жертвы
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (grabbedToAdmin.containsKey(player.getUuid())) return ActionResult.FAIL;
            return ActionResult.PASS;
        });

        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (grabbedToAdmin.containsKey(player.getUuid())) return ActionResult.FAIL;
            return ActionResult.PASS;
        });
    }

    // Вспомогательный метод для отпускания игрока
    private void releaseGrabbedPlayer(ServerPlayerEntity admin) {
        UUID targetId = adminToGrabbed.remove(admin.getUuid());
        if (targetId != null) {
            grabbedToAdmin.remove(targetId);
            admin.sendMessage(Text.literal("§eВы отпустили игрока."), true);

            ServerPlayerEntity target = admin.server.getPlayerManager().getPlayer(targetId);
            if (target != null) {
                target.sendMessage(Text.literal("§eВас отпустили."), true);
            }
        }
    }

    private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("le")
                .requires(source -> source.hasPermissionLevel(2))

                // Ветка ЛАЗЕРА: /le laser ...
                .then(CommandManager.literal("laser")
                        .then(CommandManager.literal("start")
                                .executes(context -> {
                                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                                    activeLaserPlayers.add(player.getUuid());
                                    player.sendMessage(Text.literal("§a[LaserEyes] Режим стрельбы из глаз ВКЛЮЧЕН!"), false);
                                    return 1;
                                })
                        )
                        .then(CommandManager.literal("stop")
                                .executes(context -> {
                                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                                    activeLaserPlayers.remove(player.getUuid());
                                    chargeTime.remove(player.getUuid());
                                    buttonHoldTimer.remove(player.getUuid());
                                    player.sendMessage(Text.literal("§c[LaserEyes] Режим стрельбы из глаз ВЫКЛЮЧЕН!"), false);
                                    return 1;
                                })
                        )
                )

                // Ветка ПОЛЁТА: /le fly ...
                .then(CommandManager.literal("fly")
                        .then(CommandManager.literal("start")
                                .executes(context -> {
                                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                                    activeFlyPlayers.add(player.getUuid());
                                    player.getAbilities().setFlySpeed(DEFAULT_FLY_SPEED * 3.0f);
                                    player.sendAbilitiesUpdate();
                                    player.sendMessage(Text.literal("§a[LaserEyes] Режим Супермен-полёта ВКЛЮЧЕН! Начальная скорость: 1 (x3)"), false);
                                    return 1;
                                })
                        )
                        .then(CommandManager.literal("stop")
                                .executes(context -> {
                                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                                    activeFlyPlayers.remove(player.getUuid());
                                    lastSprintingState.remove(player.getUuid());
                                    prevTickFallDistance.remove(player.getUuid()); // Очищаем состояние падения
                                    player.getAbilities().setFlySpeed(DEFAULT_FLY_SPEED);
                                    player.sendAbilitiesUpdate();
                                    player.sendMessage(Text.literal("§c[LaserEyes] Режим Супермен-полёта ВЫКЛЮЧЕН! Скорость сброшена."), false);
                                    return 1;
                                })
                        )
                        .then(CommandManager.literal("speed")
                                .then(CommandManager.argument("level", IntegerArgumentType.integer(1, 3))
                                        .executes(context -> {
                                            ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                                            int level = IntegerArgumentType.getInteger(context, "level");

                                            float multiplier = 3.0f;
                                            if (level == 2) {
                                                multiplier = 5.0f;
                                            } else if (level == 3) {
                                                multiplier = 7.0f;
                                            }

                                            player.getAbilities().setFlySpeed(DEFAULT_FLY_SPEED * multiplier);
                                            player.sendAbilitiesUpdate();
                                            player.sendMessage(Text.literal("§b[LaserEyes] Установлена скорость полёта: " + level + " (x" + multiplier + ")"), false);
                                            return 1;
                                        })
                                )
                        )
                )

                // Ветка ЗАХВАТА: /le grab ...
                .then(CommandManager.literal("grab")
                        .then(CommandManager.literal("start")
                                .executes(context -> {
                                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                                    activeGrabPlayers.add(player.getUuid());
                                    player.sendMessage(Text.literal("§a[LaserEyes] Режим захвата ВКЛЮЧЕН! (ПКМ по игроку)"), false);
                                    return 1;
                                })
                        )
                        .then(CommandManager.literal("stop")
                                .executes(context -> {
                                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                                    activeGrabPlayers.remove(player.getUuid());

                                    releaseGrabbedPlayer(player);

                                    player.sendMessage(Text.literal("§c[LaserEyes] Режим захвата ВЫКЛЮЧЕН!"), false);
                                    return 1;
                                })
                        )
                )
        );
    }

    private EyePoints getEyePoints(ServerPlayerEntity player) {
        Vec3d eyePos = player.getEyePos();
        Vec3d look = player.getRotationVec(1.0F);

        Vec3d horizontalLook = new Vec3d(look.x, 0, look.z);
        Vec3d right;
        if (horizontalLook.lengthSquared() < 0.0001) {
            float yaw = player.getYaw() * ((float)Math.PI / 180F);
            right = new Vec3d(-Math.cos(yaw), 0, -Math.sin(yaw)).normalize().multiply(0.125);
        } else {
            right = horizontalLook.crossProduct(new Vec3d(0, 1, 0)).normalize().multiply(0.125);
        }

        Vec3d leftEye = eyePos.subtract(right).subtract(0, 0.0625, 0);
        Vec3d rightEye = eyePos.add(right).subtract(0, 0.0625, 0);

        return new EyePoints(leftEye, rightEye, look);
    }

    private void spawnEyeGlow(ServerPlayerEntity player, ServerWorld world) {
        EyePoints points = getEyePoints(player);
        DustParticleEffect redParticle = new DustParticleEffect(new Vector3f(1.0f, 0.0f, 0.0f), 0.7f);

        Vec3d leftCenter = points.leftEye.add(points.look.multiply(0.2));
        Vec3d rightCenter = points.rightEye.add(points.look.multiply(0.2));

        for (int i = 0; i < 5; i++) {
            double ox = (Math.random() - 0.5) * 0.15;
            double oy = (Math.random() - 0.5) * 0.15;
            double oz = (Math.random() - 0.5) * 0.15;
            world.spawnParticles(redParticle, leftCenter.x + ox, leftCenter.y + oy, leftCenter.z + oz, 1, 0, 0, 0, 0);
        }

        for (int i = 0; i < 5; i++) {
            double ox = (Math.random() - 0.5) * 0.15;
            double oy = (Math.random() - 0.5) * 0.15;
            double oz = (Math.random() - 0.5) * 0.15;
            world.spawnParticles(redParticle, rightCenter.x + ox, rightCenter.y + oy, rightCenter.z + oz, 1, 0, 0, 0, 0);
        }
    }

    private void spawnSonicBoomEffect(ServerPlayerEntity player, ServerWorld world) {
        world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 1.0f, 1.0f);

        Vec3d startPos = player.getPos().add(0, 1.2, 0);
        Vec3d dir = player.getRotationVec(1.0F).normalize();
        Vec3d backDir = dir.multiply(-1);

        Vec3d upBase = new Vec3d(0, 1, 0);
        if (Math.abs(dir.y) > 0.99) {
            upBase = new Vec3d(1, 0, 0);
        }
        Vec3d right = dir.crossProduct(upBase).normalize();
        Vec3d up = right.crossProduct(dir).normalize();

        double coneLength = 1.7;
        int lengthSteps = 8;

        for (int i = 1; i <= lengthSteps; i++) {
            double distanceBehind = (coneLength / lengthSteps) * i;
            double radius = distanceBehind * 1.4;

            Vec3d ringCenter = startPos.add(backDir.multiply(distanceBehind));

            int pointsInRing = (int) (22 * radius);
            if (pointsInRing < 5) pointsInRing = 5;

            for (int p = 0; p < pointsInRing; p++) {
                double angle = 2 * Math.PI * p / pointsInRing;
                double xOffset = Math.cos(angle) * radius;
                double yOffset = Math.sin(angle) * radius;

                Vec3d particlePos = ringCenter.add(right.multiply(xOffset)).add(up.multiply(yOffset));

                world.spawnParticles(ParticleTypes.CLOUD, particlePos.x, particlePos.y, particlePos.z,
                        1, 0.05, 0.05, 0.05, 0);
            }
        }
    }

    // Эффектное «Супергеройское приземление»
    private void spawnSuperheroLandingEffect(ServerPlayerEntity player, ServerWorld world) {
        Vec3d pos = player.getPos();

        // Тяжелый звук удара
        world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_GENERIC_BIG_FALL, SoundCategory.PLAYERS, 1.0f, 0.8f);

        // Спавним центральное облако пыли
        world.spawnParticles(ParticleTypes.EXPLOSION, pos.x, pos.y, pos.z, 2, 0.2, 0.1, 0.2, 0.1);
        world.spawnParticles(ParticleTypes.CLOUD, pos.x, pos.y, pos.z, 15, 0.3, 0.1, 0.3, 0.2);

        // Получаем блок, на который мы упали, чтобы использовать его цвет для частиц
        BlockPos blockBelow = player.getBlockPos().down();
        BlockState landingBlock = world.getBlockState(blockBelow);
        BlockStateParticleEffect blockDust = new BlockStateParticleEffect(ParticleTypes.BLOCK, landingBlock);

        // Генерируем "волну" пыли в виде двух концентрических колец
        int pointsPerRing = 24;
        for (int r = 0; r < 2; r++) {
            double radius = (r == 0) ? 0.7 : 1.4; // Радиусы колец
            double velocityMultiplier = (r == 0) ? 0.4 : 0.2; // Скорость разлета

            for (int i = 0; i < pointsPerRing; i++) {
                double angle = 2 * Math.PI * i / pointsPerRing;
                double ox = Math.cos(angle) * radius;
                double oz = Math.sin(angle) * radius;

                double vx = Math.cos(angle) * velocityMultiplier;
                double vz = Math.sin(angle) * velocityMultiplier;
                double vy = (Math.random() * 0.1) + 0.1; // Слегка вверх

                // Спавним частицы с рассчитанной скоростью.
                world.spawnParticles(blockDust, pos.x + ox, pos.y + 0.1, pos.z + oz,
                        1, vx, vy, vz, 0);
            }
        }
    }

    private void fireFullLaser(ServerPlayerEntity player, ServerWorld world) {
        EyePoints points = getEyePoints(player);
        DustParticleEffect redParticle = new DustParticleEffect(new Vector3f(1.0f, 0.0f, 0.0f), 0.6f);

        for (double d = 0; d < MAX_DISTANCE; d += PARTICLE_STEP_BEAM) {
            Vec3d currentLeft = points.leftEye.add(points.look.multiply(d));
            Vec3d currentRight = points.rightEye.add(points.look.multiply(d));

            BlockPos posLeft = BlockPos.ofFloored(currentLeft);
            BlockPos posRight = BlockPos.ofFloored(currentRight);

            if (!world.getBlockState(posLeft).getCollisionShape(world, posLeft).isEmpty() ||
                    !world.getBlockState(posRight).getCollisionShape(world, posRight).isEmpty()) {
                break;
            }

            Vec3d centerPos = player.getEyePos().add(points.look.multiply(d));
            Box hitBox = new Box(centerPos.x - 0.5, centerPos.y - 0.5, centerPos.z - 0.5,
                    centerPos.x + 0.5, centerPos.y + 0.5, centerPos.z + 0.5);

            List<LivingEntity> hitEntities = world.getEntitiesByClass(LivingEntity.class, hitBox, entity -> entity != player && entity.isAlive());

            boolean hitCreativePlayer = false;

            for (LivingEntity entity : hitEntities) {
                if (entity instanceof ServerPlayerEntity targetPlayer && (targetPlayer.isCreative() || targetPlayer.isSpectator())) {
                    hitCreativePlayer = true;
                } else {
                    entity.kill();
                }
            }

            if (hitCreativePlayer) {
                break;
            }

            world.spawnParticles(redParticle, currentLeft.x, currentLeft.y, currentLeft.z, 1, 0, 0, 0, 0);
            world.spawnParticles(redParticle, currentRight.x, currentRight.y, currentRight.z, 1, 0, 0, 0, 0);
        }
    }

    private static class EyePoints {
        final Vec3d leftEye;
        final Vec3d rightEye;
        final Vec3d look;

        EyePoints(Vec3d leftEye, Vec3d rightEye, Vec3d look) {
            this.leftEye = leftEye;
            this.rightEye = rightEye;
            this.look = look;
        }
    }
}