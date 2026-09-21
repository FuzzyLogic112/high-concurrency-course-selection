package com.ncuky.cs.controller;

import com.ncuky.cs.common.R;
import com.ncuky.cs.dto.Dtos;
import com.ncuky.cs.security.UserContext;
import com.ncuky.cs.service.SelectionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 选课接口。
 * <p>
 * 注意所有方法都从 {@link UserContext} 取 studentId，不接受前端传参——
 * 否则学生改个请求体就能替别人选课。这是答辩必问的越权点。
 */
@RestController
@RequestMapping("/api/selection")
public class SelectionController {

    private final SelectionService selectionService;

    public SelectionController(SelectionService selectionService) {
        this.selectionService = selectionService;
    }

    /** 前置校验，不扣名额。前端点「选课」之前先调它给提示 */
    @PostMapping("/pre-check")
    public R<Dtos.PreCheckResp> preCheck(@Valid @RequestBody Dtos.SelectReq req) {
        return selectionService.preCheck(UserContext.studentId(), req.classId());
    }

    /** 提交选课 */
    @PostMapping("/select")
    public R<Dtos.SelectResp> select(@Valid @RequestBody Dtos.SelectReq req) {
        return selectionService.select(UserContext.studentId(), req.classId());
    }

    /** 异步链路的结果查询 */
    @GetMapping("/status")
    public R<Dtos.SelectResp> status(@RequestParam Long classId) {
        return selectionService.queryStatus(UserContext.studentId(), classId);
    }

    /** 退选 */
    @PostMapping("/drop")
    public R<Void> drop(@Valid @RequestBody Dtos.SelectReq req) {
        return selectionService.drop(UserContext.studentId(), req.classId());
    }

    /** 名额满时排队候补 */
    @PostMapping("/waiting")
    public R<Void> waiting(@Valid @RequestBody Dtos.SelectReq req) {
        return selectionService.joinWaitingQueue(UserContext.studentId(), req.classId());
    }
}
