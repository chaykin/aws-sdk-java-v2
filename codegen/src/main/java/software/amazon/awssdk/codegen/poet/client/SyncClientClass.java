/*
 * Copyright 2010-2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.codegen.poet.client;

import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static software.amazon.awssdk.codegen.poet.client.ClientClassUtils.applyPaginatorUserAgentMethod;
import static software.amazon.awssdk.codegen.poet.client.ClientClassUtils.applySignerOverrideMethod;
import static software.amazon.awssdk.codegen.poet.client.ClientClassUtils.getCustomResponseHandler;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeSpec.Builder;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.lang.model.element.Modifier;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.codegen.docs.SimpleMethodOverload;
import software.amazon.awssdk.codegen.emitters.GeneratorTaskParams;
import software.amazon.awssdk.codegen.model.intermediate.IntermediateModel;
import software.amazon.awssdk.codegen.model.intermediate.OperationModel;
import software.amazon.awssdk.codegen.model.intermediate.Protocol;
import software.amazon.awssdk.codegen.poet.ClassSpec;
import software.amazon.awssdk.codegen.poet.PoetExtensions;
import software.amazon.awssdk.codegen.poet.PoetUtils;
import software.amazon.awssdk.codegen.poet.client.specs.Ec2ProtocolSpec;
import software.amazon.awssdk.codegen.poet.client.specs.JsonProtocolSpec;
import software.amazon.awssdk.codegen.poet.client.specs.ProtocolSpec;
import software.amazon.awssdk.codegen.poet.client.specs.QueryProtocolSpec;
import software.amazon.awssdk.codegen.poet.client.specs.XmlProtocolSpec;
import software.amazon.awssdk.codegen.utils.PaginatorUtils;
import software.amazon.awssdk.core.client.config.SdkClientConfiguration;
import software.amazon.awssdk.core.client.handler.SyncClientHandler;

//TODO Make SyncClientClass extend SyncClientInterface (similar to what we do in AsyncClientClass)
public class SyncClientClass implements ClassSpec {

    private final IntermediateModel model;
    private final PoetExtensions poetExtensions;
    private final ClassName className;
    private final ProtocolSpec protocolSpec;

    public SyncClientClass(GeneratorTaskParams taskParams) {
        this.model = taskParams.getModel();
        this.poetExtensions = taskParams.getPoetExtensions();
        this.className = poetExtensions.getClientClass(model.getMetadata().getSyncClient());
        this.protocolSpec = getProtocolSpecs(poetExtensions, model);
    }

    @Override
    public TypeSpec poetSpec() {
        ClassName interfaceClass = poetExtensions.getClientClass(model.getMetadata().getSyncInterface());

        Builder classBuilder = PoetUtils.createClassBuilder(className)
                                        .addAnnotation(SdkInternalApi.class)
                                        .addModifiers(FINAL)
                                        .addSuperinterface(interfaceClass)
                                        .addJavadoc("Internal implementation of {@link $1T}.\n\n@see $1T#builder()",
                                                    interfaceClass)
                                        .addField(SyncClientHandler.class, "clientHandler", PRIVATE, FINAL)
                                        .addField(protocolSpec.protocolFactory(model))
                                        .addField(SdkClientConfiguration.class, "clientConfiguration", PRIVATE, FINAL)
                                        .addMethod(constructor())
                                        .addMethod(nameMethod())
                                        .addMethods(protocolSpec.additionalMethods())
                                        .addMethods(operations());

        protocolSpec.createErrorResponseHandler().ifPresent(classBuilder::addMethod);

        classBuilder.addMethod(protocolSpec.initProtocolFactory(model));

        classBuilder.addMethod(closeMethod());

        if (model.hasPaginators()) {
            classBuilder.addMethod(applyPaginatorUserAgentMethod(poetExtensions, model));
        }

        if (model.containsRequestSigners()) {
            classBuilder.addMethod(applySignerOverrideMethod(poetExtensions, model));
        }

        return classBuilder.build();
    }

    private MethodSpec nameMethod() {
        return MethodSpec.methodBuilder("serviceName")
                         .addAnnotation(Override.class)
                         .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                         .returns(String.class)
                         .addStatement("return SERVICE_NAME")
                         .build();
    }

    @Override
    public ClassName className() {
        return className;
    }

    private MethodSpec constructor() {
        MethodSpec.Builder builder = MethodSpec.constructorBuilder()
                                               .addModifiers(Modifier.PROTECTED)
                                               .addParameter(SdkClientConfiguration.class, "clientConfiguration")
                                               .addStatement("this.clientHandler = new $T(clientConfiguration)",
                                                             protocolSpec.getClientHandlerClass())
                                               .addStatement("this.clientConfiguration = clientConfiguration");
        FieldSpec protocolFactoryField = protocolSpec.protocolFactory(model);
        if (model.getMetadata().isJsonProtocol()) {
            builder.addStatement("this.$N = init($T.builder()).build()", protocolFactoryField.name,
                                 protocolFactoryField.type);
        } else {
            builder.addStatement("this.$N = init()", protocolFactoryField.name);
        }
        return builder.build();
    }

    private List<MethodSpec> operations() {
        return model.getOperations().values().stream()
                    .filter(o -> !o.hasEventStreamInput())
                    .filter(o -> !o.hasEventStreamOutput())
                    .map(this::operationMethodSpecs)
                    .flatMap(List::stream)
                    .collect(Collectors.toList());
    }

    private List<MethodSpec> operationMethodSpecs(OperationModel opModel) {
        List<MethodSpec> methods = new ArrayList<>();
        ClassName returnType = poetExtensions.getModelClass(opModel.getReturnType().getReturnType());

        methods.add(SyncClientInterface.operationMethodSignature(model, opModel)
                                       .addAnnotation(Override.class)
                                       .addCode(ClientClassUtils.callApplySignerOverrideMethod(opModel))
                                       .addCode(getCustomResponseHandler(opModel, returnType)
                                                    .orElseGet(() -> protocolSpec.responseHandler(model, opModel)))
                                       .addCode(protocolSpec.errorResponseHandler(opModel))
                                       .addCode(protocolSpec.executionHandler(opModel))
                                       .build());

        methods.addAll(paginatedMethods(opModel));

        return methods;
    }

    private List<MethodSpec> paginatedMethods(OperationModel opModel) {
        List<MethodSpec> paginatedMethodSpecs = new ArrayList<>();

        if (opModel.isPaginated()) {
            paginatedMethodSpecs.add(SyncClientInterface.operationMethodSignature(model,
                                                                                  opModel,
                                                                                  SimpleMethodOverload.PAGINATED,
                                                                                  PaginatorUtils.getPaginatedMethodName(
                                                                                      opModel.getMethodName()))
                                                        .addAnnotation(Override.class)
                                                        .returns(poetExtensions.getResponseClassForPaginatedSyncOperation(
                                                            opModel.getOperationName()))
                                                        .addStatement("return new $T(this, applyPaginatorUserAgent($L))",
                                                                      poetExtensions.getResponseClassForPaginatedSyncOperation(
                                                                          opModel.getOperationName()),
                                                                      opModel.getInput().getVariableName())
                                                        .build());
        }

        return paginatedMethodSpecs;
    }

    private MethodSpec closeMethod() {
        return MethodSpec.methodBuilder("close")
                         .addAnnotation(Override.class)
                         .addStatement("clientHandler.close()")
                         .addModifiers(Modifier.PUBLIC)
                         .build();
    }

    static ProtocolSpec getProtocolSpecs(PoetExtensions poetExtensions, IntermediateModel model) {
        Protocol protocol = model.getMetadata().getProtocol();
        switch (protocol) {
            case QUERY:
                return new QueryProtocolSpec(poetExtensions);
            case REST_XML:
                return new XmlProtocolSpec(poetExtensions);
            case EC2:
                return new Ec2ProtocolSpec(poetExtensions);
            case AWS_JSON:
            case REST_JSON:
            case CBOR:
            case ION:
                return new JsonProtocolSpec(poetExtensions, model);
            case API_GATEWAY:
                throw new UnsupportedOperationException("Not yet supported.");
            default:
                throw new RuntimeException("Unknown protocol: " + protocol.name());
        }
    }
}
