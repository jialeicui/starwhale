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

package ai.starwhale.mlops.domain.swds.objectstore;

import ai.starwhale.mlops.exception.SWValidationException;
import ai.starwhale.mlops.exception.SWValidationException.ValidSubject;
import ai.starwhale.mlops.storage.fs.FileStorageEnv;
import ai.starwhale.mlops.storage.fs.FileStorageEnv.FileSystemEnvType;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

@Slf4j
public class StorageAuths {

    Map<String, FileStorageEnv> envMap = new HashMap<>();

    static final String NAME_DEFAULT="";

    static final Pattern LINE_PATTERN=Pattern.compile("^(USER\\.(S3|HDFS|WEBHDFS|LOCALFS|NFS|FTP|SFTP|HTTP|HTTPS)\\.((\\w+)\\.)?(\\w+))=(\\w+)$");
    public StorageAuths(String authsText){
        String[] lines = authsText.split("\n");
        Stream.of(lines).forEach(line->{
            Matcher matcher = LINE_PATTERN.matcher(line);
            if(!matcher.matches()){
                log.warn("unsupported auth line {} ",line);
                return;
            }
            String type = matcher.group(2);
            String name = matcher.group(4);
            String envName = matcher.group(1);
            String envValue = matcher.group(6);
            if(!StringUtils.hasText(name)){
                name = NAME_DEFAULT;
            }
            FileStorageEnv fileStorageEnv = envMap.computeIfAbsent(name,
                k -> new FileStorageEnv(FileSystemEnvType.valueOf(type)));
            fileStorageEnv.add(envName,envValue);
        });

    }

    public FileStorageEnv getEnv(String authName){
        if(!StringUtils.hasText(authName)){
            return envMap.get(NAME_DEFAULT);
        }
        return envMap.get(authName);
    }

    public Map<String, FileStorageEnv> allEnvs(){
        return envMap;
    }

}