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

package ai.starwhale.mlops.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import javax.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

@Tag(name = "Object Store")
@Validated
public interface ObjectStoreApi {

    @Operation(summary = "Get the content of an object or a file")
    @ApiResponses(
            value = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "ok",
                            content =
                            @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = List.class)))
            })
    @GetMapping(
            value = "/obj-store/{path}/{expTimeMillis}",
            produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    void getObjectContent(
            @Parameter(in = ParameterIn.PATH, required = true, schema = @Schema())
            @PathVariable("path") String path,
            @RequestHeader(name = "Range", required = false) String range,
            @Parameter(in = ParameterIn.PATH, required = true, schema = @Schema())
            @PathVariable("expTimeMillis") Long expTimeMillis,
            HttpServletResponse httpResponse);
}