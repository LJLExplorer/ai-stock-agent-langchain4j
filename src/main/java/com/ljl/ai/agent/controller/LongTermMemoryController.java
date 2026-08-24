package com.ljl.ai.agent.controller;

import com.ljl.ai.agent.model.dto.LongTermMemoryRequest;
import com.ljl.ai.agent.model.entity.UserLongTermMemory;
import com.ljl.ai.agent.service.LongTermMemoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/memories")
@RequiredArgsConstructor
public class LongTermMemoryController {
    private final LongTermMemoryService service;

    @PostMapping
    public UserLongTermMemory add(@Valid @RequestBody LongTermMemoryRequest request) {
        return service.add(request.getUserId(), request.getContent(), request.getTags());
    }

    @GetMapping
    public List<UserLongTermMemory> list(@RequestParam String userId) {
        return service.list(userId);
    }

    @GetMapping("/recall")
    public List<UserLongTermMemory> recall(@RequestParam String userId, @RequestParam String query) {
        return service.recall(userId, query);
    }

    @DeleteMapping("/{memoryId}")
    public ResponseEntity<Map<String, Object>> delete(@RequestParam String userId,
                                                       @PathVariable String memoryId) {
        service.delete(userId, memoryId);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
