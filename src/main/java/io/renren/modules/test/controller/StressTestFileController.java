package io.renren.modules.test.controller;

import io.renren.common.annotation.SysLog;
import io.renren.common.utils.PageUtils;
import io.renren.common.utils.Query;
import io.renren.common.utils.R;
import io.renren.common.validator.ValidatorUtils;
import io.renren.modules.test.entity.StressTestFileEntity;
import io.renren.modules.test.jmeter.JmeterStatEntity;
import io.renren.modules.test.service.StressTestFileService;
import io.renren.modules.test.utils.StressTestUtils;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 压力测试用例文件
 */
@RestController
@RequestMapping("/test/stressFile")
public class StressTestFileController {
    Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired
    private StressTestFileService stressTestFileService;

    /**
     * 参数化文件，用例文件列表
     */
    @RequestMapping("/list")
    @RequiresPermissions("test:stress:fileList")
    public R list(@RequestParam Map<String, Object> params) {
        //查询列表数据
        Query query = new Query(StressTestUtils.filterParms(params));
        List<StressTestFileEntity> jobList = stressTestFileService.queryList(query);
        int total = stressTestFileService.queryTotal(query);

        PageUtils pageUtil = new PageUtils(jobList, total, query.getLimit(), query.getPage());

        return R.ok().put("page",pageUtil);
    }

    /**
     * 查询具体文件信息
     */
    @RequestMapping("/info/{fileId}")
    public R info(@PathVariable("fileId") Long fileId) {
        StressTestFileEntity stressTestFile = stressTestFileService.queryObject(fileId);
        return R.ok().put("stressTestFile",stressTestFile);
    }

    /**
     * 修改性能测试用例脚本文件
     */
    @SysLog("修改性能测试用例脚本文件")
    @RequestMapping("/update")
    @RequiresPermissions("test:stress:fileUpdate")
    public R update(@RequestBody StressTestFileEntity stressTestFile) {
        ValidatorUtils.validateEntity(stressTestFile);

        if (stressTestFile.getFileIdList() != null && stressTestFile.getFileIdList().length > 0) {
            stressTestFileService.updateStatusBatch(stressTestFile);
        }else {
            stressTestFileService.updateStatusBatch(stressTestFile);
        }

        return R.ok();
    }

    /**
     * 删除指定文件
     */
    @SysLog("删除性能测试用例文件")
    @RequestMapping("/delete")
    @RequiresPermissions("test:stress:fileDelete")
    public R delete(@RequestBody Long[] fileIds) {
        stressTestFileService.deleteBatch(fileIds);

        return R.ok();
    }

    /**
     * 立即执行性能测试脚本。
     */
    @SysLog("立即执行性能测试脚本")
    @RequestMapping("/runOnce")
    @RequiresPermissions("test:stress:runOnce")
    public R run(@RequestBody Long[] fileIds) {
        return R.ok(stressTestFileService.run(fileIds));
    }

    /**
     * 立即停止性能测试，仅有使用api方式时，才可以单独停止
     */
    @SysLog("立即停止性能测试用例脚本文件")
    @RequestMapping("/stopOnce")
    @RequiresPermissions("test:stress:stopOnce")
    public R stop(@RequestBody Long[] fileIds) {
        stressTestFileService.stop(fileIds);
        return R.ok();
    }

    /**
     * 停止性能测试用例
     */
    @SysLog("停止执行性能测试用例脚本")
    @RequestMapping("/stopAll")
    @RequiresPermissions("test:stress:stopAll")
    public R stopAll() {
        stressTestFileService.stopAll();
        return R.ok();
    }

    /**
     * 立即停止性能测试用例，如果是脚本方式运行希望是杀掉进程（节点机+主节点）
     */
    @SysLog("立即停止性能测试用例脚本")
    @RequestMapping("/stopAllNow")
    @RequiresPermissions("test:stress:stopAllNow")
    public R stopAllNow() {
        stressTestFileService.stopAllNow();
        return R.ok();
    }

    /**
     * 定时查询执行结果。
     * 只有在本地执行性能测试时，才会被调用。
     * 不要求权限校验了，频繁操作不用每次都调用数据库。
     */
    @RequestMapping("/statInfo/{fileId}")
    public R statInfo(@PathVariable("fileId") Long fileId) {
        logger.debug("请求url：" + "test/stressFile/statInfo/" + fileId);
        //频率不是特别高，可以是new一个对象
        JmeterStatEntity jmeterStatEntity = stressTestFileService.getJmeterStatEntity(fileId);
        logger.debug("jmeterStatEntity: " + jmeterStatEntity);
//        logger.debug("jmeterStatEntity:" + jmeterStatEntity.getResponseTimeMap());
//        logger.debug("jmeterStatEntity:" + jmeterStatEntity.getResponseTimeMap().size());

        return R.ok().put("statInfo",jmeterStatEntity);
    }

    /**
     * 将参数化文件同步到指定分布式slave节点机的指定目录下
     */
    @SysLog("将参数化文件同步到指定分布式slave节点机的指定目录")
    @RequestMapping("/synchronizeFile")
    public R synchronizeFile(@RequestBody Long[] fileIds) {
        stressTestFileService.synchronizeFile(fileIds);
        return R.ok();
    }

    /**
     * 下载文件
     */
    @RequestMapping("/downloadFile/{fileId}")
    @RequiresPermissions("test:stress:fileDownLoad")
    public ResponseEntity<InputStreamResource> downloadFile(@PathVariable("fileId") Long fileId) throws IOException {
        StressTestFileEntity stressTestFile = stressTestFileService.queryObject(fileId);
        FileSystemResource fileResource = new FileSystemResource(stressTestFileService.getFilePath(stressTestFile));

        HttpHeaders headers = new HttpHeaders();
        /**
         * HTTP1.1中启用Cache-Control 来控制页面的缓存与否
         * no-cache，浏览器和缓存服务器都不应该缓存页面信息；
         * no-store，请求和响应的信息都不应该被存储在对方的磁盘系统中；
         * must-revalidate，对于客户机的每次请求，代理服务器必须想服务器验证缓存是否过时
         *
         * Content-Disposition为属性名 disposition-type是以什么方式下载，如attachment为以附件方式下载
         * 当响应类型为application/octet- stream时使用了这个头信息的话，就意味着你不想直接显示内容，而是弹出一个"文件下载"的对话框
         * Pragma: no-cache：跟Cache-Control: no-cache相同，Pragma: no-cache兼容http 1.0 ，Cache-Control: no-cache是http 1.1提供的。
         * 注意事项：https://blog.csdn.net/qq_29347295/article/details/53066857
         *
         * Content-Type:MediaType，即是Internet Media Type，互联网媒体类型；也叫做MIME类型，
         * 在Http协议消息头中，使用Content-Type来表示具体请求中的媒体类型信息。
         * "application/octet-stream" 表示二进制流数据（如常见的文件下载）
         */
        headers.add("Cache-Control", "no-cache,no-store,must-revalidate");
        headers.add("Content-Disposition","attachment;filename=" + stressTestFile.getOriginName());
        headers.add("Pragma", "no-cache");
        headers.add("Expires", "0");
        headers.setContentType(MediaType.parseMediaType("application/octet-stream"));


        return ResponseEntity.ok()
                .headers(headers)
                .contentLength(fileResource.contentLength())
                .body(new InputStreamResource(fileResource.getInputStream()));

    }

}
