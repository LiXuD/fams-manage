package com.fams.masterdata;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("fams-product")
@MapperScan(basePackageClasses = IdentityMasterDataMapper.class, annotationClass = Mapper.class)
class MasterDataConfiguration {
}
