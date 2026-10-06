package dev.deadinternet.controller;

import dev.deadinternet.capture.CaptureService;
import dev.deadinternet.capture.CaptureStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Live capture of a real X / LinkedIn thread in a browser window the server drives. */
@RestController
@RequestMapping("/api")
public class CaptureController {

    public record CaptureRequest(String url) {}

    public record SignInRequest(String site) {}

    public record SignInStatus(boolean open) {}

    private final CaptureService captures;

    public CaptureController(CaptureService captures) {
        this.captures = captures;
    }

    @PostMapping("/captures")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CaptureStatus start(@RequestBody CaptureRequest request) {
        return captures.start(request.url());
    }

    @GetMapping("/captures/{id}")
    public CaptureStatus status(@PathVariable String id) {
        return captures.status(id);
    }

    /** Latest screenshot of the page being captured. */
    @GetMapping("/captures/{id}/frame")
    public ResponseEntity<byte[]> frame(@PathVariable String id) {
        return captures.frame(id)
                .map(bytes -> ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
                        .cacheControl(CacheControl.noStore()).body(bytes))
                .orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/captures/{id}/stop")
    public CaptureStatus stop(@PathVariable String id) {
        return captures.stop(id);
    }

    /** Opens a plain Chrome window on the site's login page; poll GET to learn when the user has closed it. */
    @PostMapping("/browser/sign-in")
    public SignInStatus signIn(@RequestBody SignInRequest request) {
        captures.openSignIn(request.site());
        return new SignInStatus(captures.signInOpen());
    }

    @GetMapping("/browser/sign-in")
    public SignInStatus signInStatus() {
        return new SignInStatus(captures.signInOpen());
    }

    @PostMapping("/browser/show")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void showBrowser() {
        captures.showBrowser();
    }
}
