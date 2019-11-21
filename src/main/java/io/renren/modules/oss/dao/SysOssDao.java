package io.renren.modules.oss.dao;

import io.renren.modules.oss.entity.SysOssEntity;
import io.renren.modules.sys.dao.BaseDao;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文件上传
 */
@Mapper
public interface SysOssDao extends BaseDao<SysOssEntity> {

}
