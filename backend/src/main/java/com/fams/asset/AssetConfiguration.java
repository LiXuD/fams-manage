package com.fams.asset;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("fams-product")
@MapperScan(basePackageClasses = AssetMapper.class, annotationClass = Mapper.class)
class AssetConfiguration { }
