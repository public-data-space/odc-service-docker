package de.fraunhofer.fokus.ids.main;

import de.fraunhofer.fokus.ids.services.InitService;
import de.fraunhofer.fokus.ids.services.database.DatabaseServiceVerticle;
import de.fraunhofer.fokus.ids.services.docker.DockerService;
import de.fraunhofer.fokus.ids.services.docker.DockerServiceVerticle;
import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.*;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import org.apache.http.entity.ContentType;

import java.util.HashSet;
import java.util.Set;
/**
 * @author Vincent Bohlen, vincent.bohlen@fokus.fraunhofer.de
 */
public class MainVerticle extends AbstractVerticle {

    private static Logger LOGGER = LoggerFactory.getLogger(MainVerticle.class.getName());
    private Router router;
    private DockerService dockerService;
    private int servicePort;

    @Override
    public void start(Future<Void> startFuture) {
        this.router = Router.router(vertx);

        DeploymentOptions deploymentOptions = new DeploymentOptions();
        deploymentOptions.setWorker(true);

        Future<String> deployment = Future.succeededFuture();
        deployment
                .compose(id1 -> {
                    Future<String> databaseDeploymentFuture = Future.future();
                    vertx.deployVerticle(DatabaseServiceVerticle.class.getName(), deploymentOptions, databaseDeploymentFuture.completer());
                    return databaseDeploymentFuture;
                })
                .compose(id2 -> {
                    Future<String> dockerServiceFuture = Future.future();
                    vertx.deployVerticle(DockerServiceVerticle.class.getName(), deploymentOptions, dockerServiceFuture.completer());
                    return dockerServiceFuture;
                })
                .compose(id3 -> {
                    Future<String> envFuture = Future.future();
                    ConfigStoreOptions confStore = new ConfigStoreOptions()
                            .setType("env");
                    ConfigRetrieverOptions options = new ConfigRetrieverOptions().addStore(confStore);
                    ConfigRetriever retriever = ConfigRetriever.create(vertx, options);
                    retriever.getConfig(ar -> {
                        if (ar.succeeded()) {
                            servicePort = ar.result().getInteger("SERVICE_PORT");
                            envFuture.complete();
                        } else {
                            envFuture.fail(ar.cause());
                        }
                    });
                    return envFuture;
                }).setHandler( ar -> {
            if(ar.succeeded()){
                this.dockerService = DockerService.createProxy(vertx, "de.fraunhofer.fokus.ids.dockerService");
                new InitService(vertx, reply -> {
                    if(reply.succeeded()){
                        LOGGER.info("Initialization complete.");
                        createHttpServer();
                        startFuture.complete();
                    }
                    else{
                        LOGGER.error("Initialization failed.");
                        startFuture.fail(reply.cause());
                    }
                });
            }
            else{
                startFuture.fail(ar.cause());
            }
        });
    }

    private void createHttpServer() {
        HttpServer server = vertx.createHttpServer();

        Set<String> allowedHeaders = new HashSet<>();
        allowedHeaders.add("x-requested-with");
        allowedHeaders.add("Access-Control-Allow-Origin");
        allowedHeaders.add("origin");
        allowedHeaders.add("authorization");
        allowedHeaders.add("Content-Type");
        allowedHeaders.add("accept");
        allowedHeaders.add("X-PINGARUNER");

        Set<HttpMethod> allowedMethods = new HashSet<>();
        allowedMethods.add(HttpMethod.GET);
        allowedMethods.add(HttpMethod.POST);

        router.route().handler(CorsHandler.create("*").allowedHeaders(allowedHeaders).allowedMethods(allowedMethods));
        router.route().handler(BodyHandler.create());

        router.route("/images").handler(routingContext ->  findImages(reply -> reply(reply, routingContext.response())));

        router.route("/images/start/:id").handler(routingContext ->  startContainer(routingContext.request().getParam("id"),reply -> reply(reply, routingContext.response())));

        router.post("/images/stop/").handler(routingContext ->  stopContainer(routingContext.getBodyAsJson().getJsonArray("data"),reply -> reply(reply, routingContext.response())));

        LOGGER.info("Starting Config manager");
        server.requestHandler(router).listen(servicePort);
        LOGGER.info("Config manager successfully started om port "+servicePort);
    }

    private void startContainer(String imageId, Handler<AsyncResult<JsonObject>> resultHandler){
        dockerService.startContainer(imageId, reply -> {
            if(reply.succeeded()){
                resultHandler.handle(Future.succeededFuture(reply.result()));
            }
            else{
                LOGGER.error(reply.cause());
                resultHandler.handle(Future.failedFuture(reply.cause()));
            }
        });
    }

    private void stopContainer(JsonArray imageIds, Handler<AsyncResult<JsonObject>> resultHandler){
        dockerService.stopContainer(imageIds, reply -> {
            if(reply.succeeded()){
                resultHandler.handle(Future.succeededFuture(new JsonObject().put("data",reply.result())));
            }
            else{
                LOGGER.error(reply.cause());
                resultHandler.handle(Future.failedFuture(reply.cause()));
            }
        });
    }

    private void reply(AsyncResult result, HttpServerResponse response) {
        if (result.succeeded()) {
            if (result.result() != null) {
                String entity = result.result().toString();
                response.putHeader("content-type", ContentType.APPLICATION_JSON.toString());
                response.end(entity);
            } else {
                response.setStatusCode(404).end();
            }
        } else {
            response.setStatusCode(404).end();
        }
    }

    private void findImages(Handler<AsyncResult<JsonObject>> resultHandler){
        this.dockerService.findImages(reply -> {
            if(reply.succeeded()){
                resultHandler.handle(Future.succeededFuture(new JsonObject().put("data",reply.result())));
            }
            else{
                LOGGER.error("Images could not be loaded.", reply.cause());
                resultHandler.handle(Future.failedFuture(reply.cause()));
            }
        });
    }
}
