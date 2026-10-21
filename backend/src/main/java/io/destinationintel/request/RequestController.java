package io.destinationintel.request;

import io.destinationintel.common.ClientIpResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/requests")
@Tag(name = "Destination requests", description = "Request a missing destination and track it, without an account")
public class RequestController {

    public record SubmitBody(@NotBlank @Size(min = 2, max = 120) String query, @Size(max = 60) String state,
                             @Size(max = 254) String email) {}

    public record NotifyBody(@NotBlank @Size(max = 254) String email) {}

    public record ChooseBody(@NotBlank @Pattern(regexp = "^Q[0-9]+$") String wikidataId) {}

    private final RequestService requests;
    private final ClientIpResolver ip;

    public RequestController(RequestService requests, ClientIpResolver ip) {
        this.requests = requests;
        this.ip = ip;
    }

    @PostMapping
    @Operation(summary = "Request a destination; duplicates are merged and counted as demand")
    public ResponseEntity<RequestService.RequestView> submit(@Valid @RequestBody SubmitBody body, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(requests.submit(body.query(), body.state(), body.email(), ip.resolve(request)));
    }

    @GetMapping("/{code}")
    @Operation(summary = "Status of a destination request by its tracking code")
    public RequestService.RequestView status(@PathVariable String code) {
        return requests.view(code);
    }

    @PostMapping("/{code}/notify")
    @Operation(summary = "Email me once when this request's guide is ready (opt-in; the address is erased after sending)")
    public ResponseEntity<RequestService.RequestView> notifyMe(@PathVariable String code, @Valid @RequestBody NotifyBody body,
                                                               HttpServletRequest request) {
        return ResponseEntity.accepted().body(requests.notifyMe(code, body.email(), ip.resolve(request)));
    }

    @PostMapping("/{code}/choose")
    @Operation(summary = "Choose which place an ambiguous request refers to")
    public RequestService.RequestView choose(@PathVariable String code, @Valid @RequestBody ChooseBody body,
                                             HttpServletRequest request) {
        return requests.choose(code, body.wikidataId(), ip.resolve(request));
    }
}
