package com.ljl.ai.controller;

import com.ljl.ai.model.dto.LongTermMemoryRequest;
import com.ljl.ai.model.entity.UserLongTermMemory;
import com.ljl.ai.service.LongTermMemoryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class LongTermMemoryControllerTest {
    @Test
    void shouldDelegateMemoryCreationAndRecallToService() {
        LongTermMemoryService service = mock(LongTermMemoryService.class);
        LongTermMemoryController controller = new LongTermMemoryController(service);
        LongTermMemoryRequest request = new LongTermMemoryRequest();
        request.setUserId("user-1");
        request.setContent("关注新能源");
        when(service.add("user-1", "关注新能源", null)).thenReturn(UserLongTermMemory.builder().build());
        when(service.recall("user-1", "投资偏好")).thenReturn(List.of());

        controller.add(request);
        List<UserLongTermMemory> result = controller.recall("user-1", "投资偏好");

        verify(service).add("user-1", "关注新能源", null);
        verify(service).recall("user-1", "投资偏好");
        assertEquals(0, result.size());
    }
}
