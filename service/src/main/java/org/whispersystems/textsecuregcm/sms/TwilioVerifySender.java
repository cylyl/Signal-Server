package org.whispersystems.textsecuregcm.sms;

import static org.whispersystems.textsecuregcm.metrics.MetricsUtil.name;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale.LanguageRange;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;
import javax.validation.constraints.NotEmpty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.configuration.TwilioConfiguration;
import org.whispersystems.textsecuregcm.http.FaultTolerantHttpClient;
import org.whispersystems.textsecuregcm.http.FormDataBodyPublisher;
import org.whispersystems.textsecuregcm.metrics.UserAgentTagUtil;
import org.whispersystems.textsecuregcm.util.SystemMapper;
import org.whispersystems.textsecuregcm.util.Util;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
class TwilioVerifySender {

  private static final Logger logger = LoggerFactory.getLogger(TwilioVerifySender.class);

  private static final String VERIFICATION_SUCCEEDED_RESPONSE_COUNTER_NAME = name(TwilioVerifySender.class,
      "verificationSucceeded");

  private static final String CONTEXT_TAG_NAME = "context";
  private static final String STATUS_CODE_TAG_NAME = "statusCode";
  private static final String ERROR_CODE_TAG_NAME = "errorCode";

  static final Set<String> TWILIO_VERIFY_LANGUAGES = Set.of(
      "af",
      "ar",
      "ca",
      "zh",
      "zh-CN",
      "zh-HK",
      "hr",
      "cs",
      "da",
      "nl",
      "en",
      "en-GB",
      "fi",
      "fr",
      "de",
      "el",
      "he",
      "hi",
      "hu",
      "id",
      "it",
      "ja",
      "ko",
      "ms",
      "nb",
      "pl",
      "pt",
      "pt-BR",
      "ro",
      "ru",
      "es",
      "sv",
      "tl",
      "th",
      "tr",
      "vi");

  private final String accountId;
  private final String accountToken;

  private final URI verifyServiceUri;
  private final URI verifyApprovalBaseUri;
  private final String androidAppHash;
  private final String verifyServiceFriendlyName;
  private final FaultTolerantHttpClient httpClient;

  TwilioVerifySender(String baseUri, FaultTolerantHttpClient httpClient, TwilioConfiguration twilioConfiguration) {

    this.accountId = twilioConfiguration.getAccountId();
    this.accountToken = twilioConfiguration.getAccountToken();

    this.verifyServiceUri = URI
        .create(baseUri + "/v2/Services/" + twilioConfiguration.getVerifyServiceSid() + "/Verifications");
    this.verifyApprovalBaseUri = URI
        .create(baseUri + "/v2/Services/" + twilioConfiguration.getVerifyServiceSid() + "/Verifications/");

    this.androidAppHash = twilioConfiguration.getAndroidAppHash();
    this.verifyServiceFriendlyName = twilioConfiguration.getVerifyServiceFriendlyName();
    this.httpClient = httpClient;
  }

  CompletableFuture<Optional<String>> deliverSmsVerificationWithVerify(String destination, Optional<String> clientType,
      String verificationCode, List<LanguageRange> languageRanges) {

    HttpRequest request = buildVerifyRequest("sms", destination, verificationCode, findBestLocale(languageRanges),
        clientType);

    return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(this::parseResponse)
        .handle((response, throwable) -> extractVerifySid(response, throwable, destination));
  }

  private Optional<String> findBestLocale(List<LanguageRange> priorityList) {
    return Util.findBestLocale(priorityList, TwilioVerifySender.TWILIO_VERIFY_LANGUAGES);
  }

  private TwilioVerifyResponse parseResponse(HttpResponse<String> response) {
    ObjectMapper mapper = SystemMapper.getMapper();

    if (response.statusCode() >= 200 && response.statusCode() < 300) {
      if ("application/json".equals(response.headers().firstValue("Content-Type").orElse(null))) {
        return new TwilioVerifyResponse(TwilioVerifyResponse.SuccessResponse.fromBody(mapper, response.body()));
      } else {
        return new TwilioVerifyResponse(new TwilioVerifyResponse.SuccessResponse());
      }
    }

    if ("application/json".equals(response.headers().firstValue("Content-Type").orElse(null))) {
      return new TwilioVerifyResponse(TwilioVerifyResponse.FailureResponse.fromBody(mapper, response.body()));
    } else {
      return new TwilioVerifyResponse(new TwilioVerifyResponse.FailureResponse());
    }
  }

  CompletableFuture<Optional<String>> deliverVoxVerificationWithVerify(String destination,
      String verificationCode, List<LanguageRange> languageRanges) {

    HttpRequest request = buildVerifyRequest("call", destination, verificationCode, findBestLocale(languageRanges),
        Optional.empty());

    return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(this::parseResponse)
        .handle((response, throwable) -> extractVerifySid(response, throwable, destination));
  }

  private Optional<String> extractVerifySid(TwilioVerifyResponse twilioVerifyResponse, Throwable throwable,
      String destination) {

    if (throwable != null) {
      logger.warn("Failed to send Twilio request", throwable);
      return Optional.empty();
    }

    if (twilioVerifyResponse.isFailure()) {
      Metrics.counter(TwilioSmsSender.FAILED_REQUEST_COUNTER_NAME,
          TwilioSmsSender.SERVICE_NAME_TAG, "verify",
          TwilioSmsSender.STATUS_CODE_TAG_NAME, String.valueOf(twilioVerifyResponse.failureResponse.status),
          TwilioSmsSender.ERROR_CODE_TAG_NAME, String.valueOf(twilioVerifyResponse.failureResponse.code)).increment();

      logger.info("Failed with code={}, country={}",
          twilioVerifyResponse.failureResponse.code,
          Util.getCountryCode(destination));

      return Optional.empty();
    }

    return Optional.ofNullable(twilioVerifyResponse.successResponse.getSid());
  }

  private HttpRequest buildVerifyRequest(String channel, String destination, String verificationCode,
      Optional<String> locale, Optional<String> clientType) {

    final Map<String, String> requestParameters = new HashMap<>();
    requestParameters.put("To", destination);
    requestParameters.put("CustomCode", verificationCode);
    requestParameters.put("Channel", channel);
    requestParameters.put("CustomFriendlyName", verifyServiceFriendlyName);
    locale.ifPresent(loc -> requestParameters.put("Locale", loc));
    clientType.filter(client -> client.startsWith("android"))
        .ifPresent(ignored -> requestParameters.put("AppHash", androidAppHash));

    return HttpRequest.newBuilder()
        .uri(verifyServiceUri)
        .POST(FormDataBodyPublisher.of(requestParameters))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Authorization",
            "Basic " + Base64.getEncoder().encodeToString((accountId + ":" + accountToken).getBytes()))
        .build();
  }

  public CompletableFuture<Boolean> reportVerificationSucceeded(String verificationSid, @Nullable String userAgent,
      String context) {

    final Map<String, String> requestParameters = new HashMap<>();
    requestParameters.put("Status", "approved");

    HttpRequest request = HttpRequest.newBuilder()
        .uri(verifyApprovalBaseUri.resolve(verificationSid))
        .POST(FormDataBodyPublisher.of(requestParameters))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Authorization",
            "Basic " + Base64.getEncoder().encodeToString((accountId + ":" + accountToken).getBytes()))
        .build();

    return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(this::parseResponse)
        .handle((response, throwable) -> processVerificationSucceededResponse(response, throwable, userAgent, context));
  }

  private boolean processVerificationSucceededResponse(@Nullable final TwilioVerifyResponse response,
      @Nullable final Throwable throwable,
      final String userAgent,
      final String context) {

    if (throwable == null) {

      assert response != null;

      final Tags tags = Tags.of(Tag.of(CONTEXT_TAG_NAME, context), UserAgentTagUtil.getPlatformTag(userAgent));

      if (response.isSuccess() && "approved".equals(response.successResponse.getStatus())) {
        // the other possible values of `status` are `pending` or `canceled`, but these can never happen in a response
        // to this POST, so we don‘t consider them
        Metrics.counter(VERIFICATION_SUCCEEDED_RESPONSE_COUNTER_NAME, tags)
            .increment();

        return true;
      }

      // at this point, response.isFailure() == true
      Metrics.counter(
              VERIFICATION_SUCCEEDED_RESPONSE_COUNTER_NAME,
              Tags.of(ERROR_CODE_TAG_NAME, String.valueOf(response.failureResponse.code),
                      STATUS_CODE_TAG_NAME, String.valueOf(response.failureResponse.status))
                  .and(tags))
          .increment();
    } else {
      logger.warn("Failed to send verification succeeded", throwable);
    }

    return false;
  }

  public static class TwilioVerifyResponse {

    private SuccessResponse successResponse;
    private FailureResponse failureResponse;

    TwilioVerifyResponse(SuccessResponse successResponse) {
      this.successResponse = successResponse;
    }

    TwilioVerifyResponse(FailureResponse failureResponse) {
      this.failureResponse = failureResponse;
    }

    boolean isSuccess() {
      return successResponse != null;
    }

    boolean isFailure() {
      return failureResponse != null;
    }

    private static class SuccessResponse {

      @NotEmpty
      public String sid;

      @NotEmpty
      public String status;

      static SuccessResponse fromBody(ObjectMapper mapper, String body) {
        try {
          return mapper.readValue(body, SuccessResponse.class);
        } catch (IOException e) {
          logger.warn("Error parsing twilio success response: " + e);
          return new SuccessResponse();
        }
      }

      public String getSid() {
        return sid;
      }

      public String getStatus() {
        return status;
      }
    }

    private static class FailureResponse {

      @JsonProperty
      private int status;

      @JsonProperty
      private String message;

      @JsonProperty
      private int code;

      static FailureResponse fromBody(ObjectMapper mapper, String body) {
        try {
          return mapper.readValue(body, FailureResponse.class);
        } catch (IOException e) {
          logger.warn("Error parsing twilio response: " + e);
          return new FailureResponse();
        }
      }

    }
  }
}
