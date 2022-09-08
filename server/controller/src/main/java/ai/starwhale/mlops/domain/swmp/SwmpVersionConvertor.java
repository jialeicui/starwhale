/*
 * Copyright 2022 Starwhale, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.starwhale.mlops.domain.swmp;

import ai.starwhale.mlops.api.protocol.swmp.SwModelPackageVersionVo;
import ai.starwhale.mlops.common.Convertor;
import ai.starwhale.mlops.common.IdConvertor;
import ai.starwhale.mlops.common.LocalDateTimeConvertor;
import ai.starwhale.mlops.domain.swmp.po.SwModelPackageVersionEntity;
import ai.starwhale.mlops.domain.user.UserConvertor;
import ai.starwhale.mlops.exception.ConvertException;
import java.util.Objects;
import javax.annotation.Resource;
import org.springframework.stereotype.Component;

@Component
public class SwmpVersionConvertor implements Convertor<SwModelPackageVersionEntity, SwModelPackageVersionVo> {

    @Resource
    private IdConvertor idConvertor;

    @Resource
    private UserConvertor userConvertor;

    @Resource
    private LocalDateTimeConvertor localDateTimeConvertor;

    @Override
    public SwModelPackageVersionVo convert(SwModelPackageVersionEntity entity)
            throws ConvertException {
        return SwModelPackageVersionVo.builder()
                .id(idConvertor.convert(entity.getId()))
                .name(entity.getVersionName())
                .owner(userConvertor.convert(entity.getOwner()))
                .tag(entity.getVersionTag())
                .meta(entity.getVersionMeta())
                .manifest(entity.getManifest())
                .createdTime(localDateTimeConvertor.convert(entity.getCreatedTime()))
                .build();
    }

    @Override
    public SwModelPackageVersionEntity revert(SwModelPackageVersionVo vo)
            throws ConvertException {
        Objects.requireNonNull(vo, "SWModelPackageVersionVo");
        return SwModelPackageVersionEntity.builder()
                .id(idConvertor.revert(vo.getId()))
                .versionName(vo.getName())
                .ownerId(idConvertor.revert(vo.getOwner().getId()))
                .versionTag(vo.getTag())
                .build();
    }
}