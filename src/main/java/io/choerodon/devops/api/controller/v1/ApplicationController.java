package io.choerodon.devops.api.controller.v1;

import java.util.List;
import java.util.Optional;

import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import springfox.documentation.annotations.ApiIgnore;

import io.choerodon.core.domain.Page;
import io.choerodon.core.exception.CommonException;
import io.choerodon.core.iam.ResourceLevel;
import io.choerodon.devops.api.dto.*;
import io.choerodon.devops.app.service.ApplicationService;
import io.choerodon.mybatis.pagehelper.annotation.SortDefault;
import io.choerodon.mybatis.pagehelper.domain.PageRequest;
import io.choerodon.mybatis.pagehelper.domain.Sort;
import io.choerodon.swagger.annotation.CustomPageRequest;
import io.choerodon.swagger.annotation.Permission;

/**
 * Created by younger on 2018/4/4.
 */
@RestController
@RequestMapping(value = "/v1/project/{projectId}/apps")
public class ApplicationController {

    private ApplicationService applicationService;

    public ApplicationController(ApplicationService applcationService) {
        this.applicationService = applcationService;
    }

    /**
     * 项目下创建应用
     *
     * @param projectId      项目id
     * @param applicationDTO 应用信息
     * @return ApplicationTemplateDTO
     */
    @Permission(level = ResourceLevel.PROJECT)
    @ApiOperation(value = "项目下创建应用")
    @PostMapping
    public ResponseEntity<ApplicationRepDTO> create(
            @ApiParam(value = "项目id", required = true)
            @PathVariable Long projectId,
            @ApiParam(value = "应用信息", required = true)
            @RequestBody ApplicationDTO applicationDTO) {
        return Optional.ofNullable(applicationService.create(projectId, applicationDTO))
                .map(target -> new ResponseEntity<>(target, HttpStatus.OK))
                .orElseThrow(() -> new CommonException("error.app.create"));
    }

    /**
     * 项目下查询单个应用信息
     *
     * @param projectId     项目id
     * @param applicationId 应用Id
     * @return ApplicationRepDTO
     */
    @Permission(level = ResourceLevel.PROJECT)
    @ApiOperation(value = "项目下查询单个应用信息")
    @GetMapping("/{applicationId}")
    public ResponseEntity<ApplicationRepDTO> queryByAppId(
            @ApiParam(value = "项目id", required = true)
            @PathVariable Long projectId,
            @ApiParam(value = "应用id", required = true)
            @PathVariable Long applicationId) {
        return Optional.ofNullable(applicationService.query(projectId, applicationId))
                .map(target -> new ResponseEntity<>(target, HttpStatus.OK))
                .orElseThrow(() -> new CommonException("error.application.query"));
    }

    /**
     * 项目下更新应用信息
     *
     * @param projectId            项目id
     * @param applicationUpdateDTO 应用Id
     * @return Boolean
     */
    @Permission(level = ResourceLevel.PROJECT)
    @ApiOperation(value = "项目下更新应用信息")
    @PutMapping
    public ResponseEntity<Boolean> update(
            @ApiParam(value = "项目id", required = true)
            @PathVariable Long projectId,
            @ApiParam(value = "应用信息", required = true)
            @RequestBody ApplicationUpdateDTO applicationUpdateDTO) {
        return Optional.ofNullable(applicationService.update(projectId, applicationUpdateDTO))
                .map(target -> new ResponseEntity<>(target, HttpStatus.OK))
                .orElseThrow(() -> new CommonException("error.application.query"));
    }


    /**
     * 项目下启用停用应用信息
     *
     * @param projectId 项目id
     * @param active    启用停用
     * @return ApplicationRepDTO
     */

    /**
     * 项目下启用停用应用信息
     *
     * @param projectId     项目id
     * @param applicationId 应用id
     * @param active        启用停用
     * @return Boolean
     */
    @Permission(level = ResourceLevel.PROJECT)
    @ApiOperation(value = "项目下启用停用应用信息")
    @PutMapping("/{applicationId}")
    public ResponseEntity<Boolean> queryByAppIdAndActive(
            @ApiParam(value = "项目id", required = true)
            @PathVariable Long projectId,
            @ApiParam(value = "应用id", required = true)
            @PathVariable Long applicationId,
            @ApiParam(value = "启用停用", required = true)
            @RequestParam Boolean active) {
        return Optional.ofNullable(applicationService.active(applicationId, active))
                .map(target -> new ResponseEntity<>(target, HttpStatus.OK))
                .orElseThrow(() -> new CommonException("error.application.active"));
    }

    /**
     * 项目下分页查询应用
     *
     * @param projectId   项目id
     * @param pageRequest 分页参数
     * @param params      参数
     * @return Page
     */
    @Permission(level = ResourceLevel.PROJECT)
    @ApiOperation(value = "项目下分页查询应用")
    @CustomPageRequest
    @PostMapping("/list_by_options")
    public ResponseEntity<Page<ApplicationRepDTO>> pageByOptions(
            @ApiParam(value = "项目Id", required = true)
            @PathVariable Long projectId,
            @ApiParam(value = "分页参数")
            @SortDefault(value = "id", direction = Sort.Direction.DESC)
            @ApiIgnore PageRequest pageRequest,
            @ApiParam(value = "查询参数")
            @RequestBody(required = false) String params) {
        return Optional.ofNullable(applicationService.listByOptions(projectId, pageRequest, params))
                .map(target -> new ResponseEntity<>(target, HttpStatus.OK))
                .orElseThrow(() -> new CommonException("error.appTemplate.get"));
    }

    /**
     * 根据环境id获取已部署正在运行实例的应用
     *
     * @param projectId 项目id
     * @return list of ApplicationRepDTO
     */

    /**
     * 根据环境id获取已部署正在运行实例的应用
     *
     * @param projectId 项目id
     * @param envId     环境id
     * @param status    实例状态
     * @return list of ApplicationRepDTO
     */
    @Permission(level = ResourceLevel.PROJECT)
    @ApiOperation(value = "根据环境id获取已部署正在运行实例的应用")
    @GetMapping("/options")
    public ResponseEntity<List<ApplicationCodeDTO>> listByEnvIdAndStatus(
            @ApiParam(value = "项目 ID", required = true)
            @PathVariable Long projectId,
            @ApiParam(value = "环境 ID", required = true)
            @RequestParam Long envId,
            @ApiParam(value = "实例运行状态", required = true)
            @RequestParam String status) {
        return Optional.ofNullable(applicationService.listByEnvId(projectId, envId, status))
                .map(target -> new ResponseEntity<>(target, HttpStatus.OK))
                .orElseThrow(() -> new CommonException("error.appName.query"));
    }

    /**
     * 项目下查询所有已经启用的应用
     *
     * @param projectId 项目id
     * @return list of ApplicationRepDTO
     */
    @Permission(level = ResourceLevel.PROJECT)
    @ApiOperation(value = "项目下查询所有已经启用的应用")
    @GetMapping
    public ResponseEntity<List<ApplicationRepDTO>> listByActive(@ApiParam(value = "项目 ID", required = true)
                                                                @PathVariable Long projectId) {
        return Optional.ofNullable(applicationService.listByActive(projectId))
                .map(target -> new ResponseEntity<>(target, HttpStatus.OK))
                .orElseThrow(() -> new CommonException("error.application.get"));
    }

    /**
     * 创建应用校验名称是否存在
     *
     * @param projectId 项目id
     * @param name      应用name
     * @return responseEntity
     */
    @Permission(level = ResourceLevel.PROJECT)
    @ApiOperation(value = "创建应用校验名称是否存在")
    @GetMapping(value = "/checkName")
    public ResponseEntity checkName(
            @ApiParam(value = "项目id", required = true)
            @PathVariable Long projectId,
            @ApiParam(value = "环境名", required = true)
            @RequestParam String name) {
        applicationService.checkName(projectId, name);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    /**
     * 创建应用校验编码是否存在
     *
     * @param projectId 项目ID
     * @param code      应用code
     * @return responseEntity
     */
    @Permission(level = ResourceLevel.PROJECT)
    @ApiOperation(value = "创建应用校验编码是否存在")
    @GetMapping(value = "/checkCode")
    public ResponseEntity checkCode(
            @ApiParam(value = "项目ID", required = true)
            @PathVariable Long projectId,
            @ApiParam(value = "环境名", required = true)
            @RequestParam String code) {
        applicationService.checkCode(projectId, code);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    /**
     * 查询应用模板
     *
     * @param projectId 项目ID
     * @return Page
     */
    @Permission(level = ResourceLevel.PROJECT)
    @ApiOperation(value = "查询所有应用模板")
    @GetMapping("/template")
    public ResponseEntity<List<ApplicationTemplateRepDTO>> listTemplate(
            @ApiParam(value = "项目ID", required = true)
            @PathVariable Long projectId) {
        return Optional.ofNullable(applicationService.listTemplate(projectId))
                .map(target -> new ResponseEntity<>(target, HttpStatus.OK))
                .orElseThrow(() -> new CommonException("error.template.get"));
    }

    /**
     * 项目下查询已经启用有版本未发布的应用
     *
     * @param projectId 项目id
     * @return list of ApplicationRepDTO
     */
    @Permission(level = ResourceLevel.PROJECT)
    @ApiOperation(value = "项目下查询所有已经启用的且未发布的且有版本的应用")
    @GetMapping(value = "/listById")
    public ResponseEntity<List<ApplicationDTO>> listByActiveAndPubAndVersion(@ApiParam(value = "项目 ID", required = true)
                                                                             @PathVariable Long projectId) {
        return Optional.ofNullable(applicationService.listByActiveAndPubAndVersion(projectId))
                .map(target -> new ResponseEntity<>(target, HttpStatus.OK))
                .orElseThrow(() -> new CommonException("error.application.get"));
    }
}
