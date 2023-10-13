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

package ai.starwhale.mlops.domain.job.template.po;

import ai.starwhale.mlops.domain.bundle.base.BundleEntity;
import java.util.Date;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemplateEntity implements BundleEntity {
    private Long id;
    private String name;
    private Long jobId;
    private Long projectId;
    private Long ownerId;
    private Date createdTime;
    private Date modifiedTime;
    private Integer isDeleted;

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Date getModifiedTime() {
        return modifiedTime;
    }
}