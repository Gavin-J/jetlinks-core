package org.jetlinks.core.defaults;

import lombok.Getter;
import org.jetlinks.core.ProtocolSupport;
import org.jetlinks.core.ProtocolSupports;
import org.jetlinks.core.config.ConfigKey;
import org.jetlinks.core.config.ConfigStorage;
import org.jetlinks.core.config.ConfigStorageManager;
import org.jetlinks.core.config.StorageConfigurable;
import org.jetlinks.core.device.DeviceConfigKey;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.device.DeviceProductOperator;
import org.jetlinks.core.metadata.DeviceMetadata;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.function.Supplier;

public class DefaultDeviceProductOperator implements DeviceProductOperator, StorageConfigurable {

    @Getter
    private final String id;

    private volatile DeviceMetadata metadata;

    private final Mono<ConfigStorage> storageMono;

    private final Supplier<Flux<DeviceOperator>> devicesSupplier;

    private long lstMetadataChangeTime;

    private static final ConfigKey<Long> lastMetadataTimeKey = ConfigKey.of("lst_metadata_time");

    private final Mono<DeviceMetadata> inLocalMetadata;

    private final Mono<DeviceMetadata> metadataMono;

    private final Mono<ProtocolSupport> protocolSupportMono;

    @Deprecated
    public DefaultDeviceProductOperator(String id,
                                        ProtocolSupports supports,
                                        ConfigStorageManager manager) {
        this(id, supports, manager, Flux::empty);
    }

    public DefaultDeviceProductOperator(String id,
                                        ProtocolSupports supports,
                                        ConfigStorageManager manager,
                                        Supplier<Flux<DeviceOperator>> supplier) {
        this.id = id;
//        this.protocolSupports = supports;
        this.storageMono = manager.getStorage("device-product:".concat(id));
        this.devicesSupplier = supplier;
        this.inLocalMetadata = Mono.fromSupplier(() -> metadata);
        this.protocolSupportMono = this
                .getConfig(DeviceConfigKey.protocol)
                .flatMap(supports::getProtocol);

        Mono<DeviceMetadata> loadMetadata = Mono
                .zip(
                        this.getProtocol().map(ProtocolSupport::getMetadataCodec),
                        this.getConfig(DeviceConfigKey.metadata),
                        this.getConfig(lastMetadataTimeKey)
                            .switchIfEmpty(Mono.defer(() -> {
                                long now = System.currentTimeMillis();
                                return this
                                        .setConfig(lastMetadataTimeKey, now)
                                        .thenReturn(now);
                            }))
                )
                .flatMap(tp3 -> tp3
                        .getT1()
                        .decode(tp3.getT2())
                        .doOnNext(decode -> {
                            this.metadata = decode;
                            this.lstMetadataChangeTime = tp3.getT3();
                        }));
        this.metadataMono = Mono
                .zip(
                        inLocalMetadata,
                        getConfig(lastMetadataTimeKey)
                )
                .flatMap(tp2 -> {
                    if (tp2.getT2().equals(lstMetadataChangeTime)) {
                        return inLocalMetadata;
                    }
                    return Mono.empty();
                })
                .switchIfEmpty(loadMetadata);
    }


    @Override
    public Mono<DeviceMetadata> getMetadata() {
        return this.metadataMono;

    }

    @Override
    public Mono<Boolean> setConfigs(Map<String, Object> conf) {
        if (conf.containsKey(DeviceConfigKey.metadata.getKey())) {
            conf.put(lastMetadataTimeKey.getKey(), System.currentTimeMillis());
        }
        return StorageConfigurable.super.setConfigs(conf);
    }

    @Override
    public Mono<Boolean> updateMetadata(String metadata) {
        return this
                .setConfigs(DeviceConfigKey.metadata.value(metadata), lastMetadataTimeKey.value(System.currentTimeMillis()))
                .doOnSuccess((v) -> this.metadata = null);
    }

    @Override
    public Mono<ProtocolSupport> getProtocol() {
        return protocolSupportMono;
    }

    @Override
    public Mono<ConfigStorage> getReactiveStorage() {
        return storageMono;
    }

    @Override
    public Flux<DeviceOperator> getDevices() {
        return devicesSupplier == null ? Flux.empty() : devicesSupplier.get();
    }
}
