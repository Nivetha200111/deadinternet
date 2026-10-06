package dev.deadinternet.controller;

import dev.deadinternet.dto.AnalysisStatus;
import dev.deadinternet.model.Conversation;
import dev.deadinternet.service.DemoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final DemoService demo;

    public DemoController(DemoService demo) {
        this.demo = demo;
    }

    @GetMapping
    public AnalysisStatus current() {
        return demo.current();
    }

    @PostMapping("/reset")
    public AnalysisStatus reset() {
        return demo.reset();
    }

    /** The raw bundled conversation, so the import dialog can show the expected input format. */
    @GetMapping("/conversation")
    public Conversation conversation() {
        return demo.conversation();
    }
}
