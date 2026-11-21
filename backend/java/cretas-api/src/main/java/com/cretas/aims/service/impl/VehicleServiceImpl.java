package com.cretas.aims.service.impl;

import com.cretas.aims.dto.warehouse.VehicleDTO;
import com.cretas.aims.entity.Vehicle;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.VehicleRepository;
import com.cretas.aims.service.VehicleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 车辆管理服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VehicleServiceImpl implements VehicleService {

    private final VehicleRepository vehicleRepository;

    @Override
    public List<VehicleDTO> getVehicles(String factoryId) {
        log.info("获取工厂所有车辆: factoryId={}", factoryId);
        return vehicleRepository.findByFactoryId(factoryId)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<VehicleDTO> getAvailableVehicles(String factoryId) {
        log.info("获取可用车辆: factoryId={}", factoryId);
        return vehicleRepository.findAvailableByFactoryId(factoryId)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<VehicleDTO> getVehiclesByStatus(String factoryId, String status) {
        log.info("按状态获取车辆: factoryId={}, status={}", factoryId, status);
        try {
            Vehicle.VehicleStatus vehicleStatus = Vehicle.VehicleStatus.valueOf(status.toLowerCase());
            return vehicleRepository.findByFactoryIdAndStatus(factoryId, vehicleStatus)
                    .stream()
                    .map(this::toDTO)
                    .collect(Collectors.toList());
        } catch (IllegalArgumentException e) {
            log.warn("无效的车辆状态: {}", status);
            return List.of();
        }
    }

    @Override
    public VehicleDTO getVehicle(String factoryId, String vehicleId) {
        log.info("获取车辆详情: factoryId={}, vehicleId={}", factoryId, vehicleId);
        Vehicle vehicle = vehicleRepository.findByIdAndFactoryId(vehicleId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("车辆不存在: " + vehicleId));
        return toDTO(vehicle);
    }

    @Override
    @Transactional
    public VehicleDTO createVehicle(String factoryId, VehicleDTO request) {
        log.info("创建车辆: factoryId={}, plateNumber={}", factoryId, request.getPlateNumber());

        request.setPlateNumber(normalizePlate(request.getPlateNumber()));
        request.setCapacity(resolveCapacityKg(request));
        // 检查车牌号是否已存在 (大小写不敏感、忽略首尾空白)
        if (!vehicleRepository.findAllByFactoryIdAndNormalizedPlate(factoryId, request.getPlateNumber()).isEmpty()) {
            throw new BusinessException(409, "车牌号已存在: " + request.getPlateNumber())
                    .withHint("请使用其他车牌号, 或编辑现有车辆")
                    .withHintTarget("plateNumber");
        }

        Vehicle vehicle = new Vehicle();
        vehicle.setFactoryId(factoryId);
        vehicle.setPlateNumber(request.getPlateNumber());
        vehicle.setDriverName(request.getDriver());
        vehicle.setDriverPhone(request.getPhone());
        vehicle.setCapacity(request.getCapacity());
        vehicle.setCurrentLoad(request.getCurrentLoad() != null ? request.getCurrentLoad() : BigDecimal.ZERO);
        vehicle.setVehicleType(request.getVehicleType());
        vehicle.setNotes(request.getNotes());
        vehicle.setStatus(Vehicle.VehicleStatus.available);

        Vehicle saved = vehicleRepository.save(vehicle);
        log.info("车辆创建成功: id={}", saved.getId());
        return toDTO(saved);
    }

    @Override
    @Transactional
    public VehicleDTO updateVehicle(String factoryId, String vehicleId, VehicleDTO request) {
        log.info("更新车辆: factoryId={}, vehicleId={}", factoryId, vehicleId);

        Vehicle vehicle = vehicleRepository.findByIdAndFactoryId(vehicleId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("车辆不存在: " + vehicleId));

        request.setCapacity(resolveCapacityKg(request));
        // 如果修改了车牌号，检查是否重复 (大小写不敏感、忽略首尾空白; 自己不算重复)
        String newPlate = request.getPlateNumber() == null ? null : normalizePlate(request.getPlateNumber());
        if (newPlate != null && !newPlate.equals(vehicle.getPlateNumber())) {
            if (vehicleRepository.findAllByFactoryIdAndNormalizedPlate(factoryId, newPlate).stream()
                    .anyMatch(other -> !vehicleId.equals(other.getId()))) {
                throw new BusinessException(409, "车牌号已存在: " + newPlate)
                        .withHint("请使用其他车牌号")
                        .withHintTarget("plateNumber");
            }
            vehicle.setPlateNumber(newPlate);
        }

        if (request.getDriver() != null) {
            vehicle.setDriverName(request.getDriver());
        }
        if (request.getPhone() != null) {
            vehicle.setDriverPhone(request.getPhone());
        }
        if (request.getCapacity() != null) {
            vehicle.setCapacity(request.getCapacity());
        }
        if (request.getVehicleType() != null) {
            vehicle.setVehicleType(request.getVehicleType());
        }
        if (request.getNotes() != null) {
            vehicle.setNotes(request.getNotes());
        }

        Vehicle saved = vehicleRepository.save(vehicle);
        log.info("车辆更新成功: id={}", saved.getId());
        return toDTO(saved);
    }

    @Override
    @Transactional
    public VehicleDTO updateVehicleStatus(String factoryId, String vehicleId, String status) {
        log.info("更新车辆状态: factoryId={}, vehicleId={}, status={}", factoryId, vehicleId, status);

        Vehicle vehicle = vehicleRepository.findByIdAndFactoryId(vehicleId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("车辆不存在: " + vehicleId));

        try {
            Vehicle.VehicleStatus newStatus = Vehicle.VehicleStatus.valueOf(status.toLowerCase());
            vehicle.setStatus(newStatus);

            // 如果状态变为可用，清空当前装载量
            if (newStatus == Vehicle.VehicleStatus.available) {
                vehicle.setCurrentLoad(BigDecimal.ZERO);
            }

            Vehicle saved = vehicleRepository.save(vehicle);
            log.info("车辆状态更新成功: id={}, status={}", saved.getId(), newStatus);
            return toDTO(saved);
        } catch (IllegalArgumentException e) {
            // 清单由 Vehicle.VehicleStatus 自己生成 —— ⛔ 不许在这里手写第二份。
            // 手写的那份曾经同时漏掉 dispatched、又多出后端没有的 in_use / retired,
            // 于是用户照着报错去填, 再吃一个 400。详见 VehicleStatus.legalValuesHint()。
            throw new BusinessException(400, "无效的车辆状态: " + status)
                    .withHint("请使用合法的状态值: " + Vehicle.VehicleStatus.legalValuesHint())
                    .withHintTarget("status");
        }
    }

    @Override
    @Transactional
    public VehicleDTO updateCurrentLoad(String factoryId, String vehicleId, BigDecimal load) {
        log.info("更新车辆装载量: factoryId={}, vehicleId={}, load={}", factoryId, vehicleId, load);

        Vehicle vehicle = vehicleRepository.findByIdAndFactoryId(vehicleId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("车辆不存在: " + vehicleId));

        vehicle.setCurrentLoad(load != null ? load : BigDecimal.ZERO);

        Vehicle saved = vehicleRepository.save(vehicle);
        log.info("车辆装载量更新成功: id={}, currentLoad={}", saved.getId(), load);
        return toDTO(saved);
    }

    @Override
    @Transactional
    public void deleteVehicle(String factoryId, String vehicleId) {
        log.info("删除车辆: factoryId={}, vehicleId={}", factoryId, vehicleId);

        Vehicle vehicle = vehicleRepository.findByIdAndFactoryId(vehicleId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("车辆不存在: " + vehicleId));

        // 软删除
        vehicle.setDeletedAt(LocalDateTime.now());
        vehicleRepository.save(vehicle);
        log.info("车辆删除成功（软删除）: id={}", vehicleId);
    }

    /** 车牌口径: 去首尾空白并转大写 (原型按大写比较); 空串视为缺失。 */
    static String normalizePlate(String plate) {
        if (plate == null || plate.isBlank()) {
            throw new BusinessException(400, "车牌号不能为空").withHintTarget("plateNumber");
        }
        return plate.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** 载重口径统一为 kg: 只传吨则 ×1000; 两者都传必须一致。都没传返回 null。 */
    static BigDecimal resolveCapacityKg(VehicleDTO request) {
        BigDecimal kg = request.getCapacity();
        BigDecimal tons = request.getCapacityTons();
        if (tons == null) {
            return kg;
        }
        if (tons.signum() < 0) {
            throw new BusinessException(400, "载重不能为负数").withHintTarget("capacityTons");
        }
        BigDecimal fromTons = tons.movePointRight(3);
        if (kg != null && kg.compareTo(fromTons) != 0) {
            throw new BusinessException(400, "载重 capacity(kg) 与 capacityTons(吨) 不一致")
                    .withHint("只传其中一个, 或保证 kg = 吨 × 1000").withHintTarget("capacityTons");
        }
        return fromTons;
    }

    /**
     * 实体转DTO
     */
    private VehicleDTO toDTO(Vehicle vehicle) {
        return VehicleDTO.builder()
                .id(vehicle.getId())
                .factoryId(vehicle.getFactoryId())
                .plateNumber(vehicle.getPlateNumber())
                .driver(vehicle.getDriverName())
                .phone(vehicle.getDriverPhone())
                .capacity(vehicle.getCapacity())
                .capacityTons(vehicle.getCapacity() == null ? null
                        : vehicle.getCapacity().movePointLeft(3).stripTrailingZeros())
                .currentLoad(vehicle.getCurrentLoad())
                .status(vehicle.getStatus() != null ? vehicle.getStatus().name() : "available")
                .vehicleType(vehicle.getVehicleType())
                .notes(vehicle.getNotes())
                .createdAt(vehicle.getCreatedAt())
                .updatedAt(vehicle.getUpdatedAt())
                .build();
    }
}
