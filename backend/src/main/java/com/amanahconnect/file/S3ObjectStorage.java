package com.amanahconnect.file;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.regions.Region;

/** S3 (or an S3-compatible service). Credentials come from the default AWS provider chain (instance role on EC2). */
final class S3ObjectStorage implements ObjectStorage {

    private final StorageProperties properties;
    private final S3Client client;
    private final S3Presigner presigner;
    private final Clock clock;

    S3ObjectStorage(StorageProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        Region region = Region.of(properties.region());
        var clientBuilder = S3Client.builder().region(region);
        var presignerBuilder = S3Presigner.builder().region(region);
        if (!properties.accessKey().isBlank() && !properties.secretKey().isBlank()) {
            var credentials = software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                    software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
            clientBuilder.credentialsProvider(credentials);
            presignerBuilder.credentialsProvider(credentials);
        }
        if (!properties.endpoint().isBlank()) {
            URI endpoint = URI.create(properties.endpoint());
            S3Configuration pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();
            clientBuilder.endpointOverride(endpoint).serviceConfiguration(pathStyle);
            presignerBuilder.endpointOverride(endpoint).serviceConfiguration(pathStyle);
        }
        this.client = clientBuilder.build();
        this.presigner = presignerBuilder.build();
    }

    @Override
    public PresignedUpload presignUpload(String key, String contentType, long size) {
        PutObjectRequest put = PutObjectRequest.builder().bucket(properties.bucket()).key(key).contentType(contentType).contentLength(size).build();
        var presigned = presigner.presignPutObject(r -> r.signatureDuration(properties.uploadTtl()).putObjectRequest(put));
        return new PresignedUpload(
                presigned.url().toString(),
                Map.of("Content-Type", contentType, "Content-Length", Long.toString(size)),
                clock.instant().plus(properties.uploadTtl()));
    }

    @Override
    public String presignDownload(String key) {
        GetObjectRequest get = GetObjectRequest.builder().bucket(properties.bucket()).key(key).build();
        Duration ttl = properties.downloadTtl();
        return presigner.presignGetObject(r -> r.signatureDuration(ttl).getObjectRequest(get)).url().toString();
    }

    @Override
    public Optional<ObjectInfo> head(String key) {
        try {
            var head = client.headObject(HeadObjectRequest.builder().bucket(properties.bucket()).key(key).build());
            return Optional.of(new ObjectInfo(head.contentLength(), head.contentType()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        client.putObject(
                PutObjectRequest.builder().bucket(properties.bucket()).key(key).contentType(contentType).contentLength((long) bytes.length).build(),
                software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes));
    }

    @Override
    public Optional<byte[]> get(String key) {
        try {
            return Optional.of(client.getObjectAsBytes(GetObjectRequest.builder().bucket(properties.bucket()).key(key).build()).asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<byte[]> getRange(String key, long from, long toInclusive) {
        try {
            return Optional.of(client.getObjectAsBytes(GetObjectRequest.builder().bucket(properties.bucket()).key(key).range("bytes=" + from + "-" + toInclusive).build()).asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(String key) {
        client.deleteObject(r -> r.bucket(properties.bucket()).key(key));
    }
}
