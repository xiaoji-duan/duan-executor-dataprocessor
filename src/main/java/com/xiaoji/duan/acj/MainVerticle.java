package com.xiaoji.duan.acj;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.apache.commons.codec.binary.Base64;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.util.StringUtils;

import io.vertx.amqpbridge.AmqpBridge;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.eventbus.MessageProducer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.bridge.BridgeEventType;
import io.vertx.ext.bridge.PermittedOptions;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.web.Cookie;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.TemplateHandler;
import io.vertx.ext.web.handler.sockjs.BridgeOptions;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.ext.web.templ.thymeleaf.ThymeleafTemplateEngine;

public class MainVerticle extends AbstractVerticle {

	private ThymeleafTemplateEngine thymeleaf = null;
	private AmqpBridge bridge = null;
	private MongoClient mongodb = null;
	private WebClient client = null;
	private EventBus eb = null;

	@Override
	public void start(Future<Void> startFuture) throws Exception {
		client = WebClient.create(vertx);

		JsonObject config = new JsonObject();
		config.put("host", "mongodb");
		config.put("port", 27017);
		config.put("keepAlive", true);
		mongodb = MongoClient.createShared(vertx, config);

		bridge = AmqpBridge.create(vertx);

		bridge.endHandler(endHandler -> {
			// Reconnect
			connectStompServer();
		});
		connectStompServer();

		// 缓存最新的清洗规则到内存
		refresh(null);

		thymeleaf = ThymeleafTemplateEngine.create(vertx);
		TemplateHandler templatehandler = TemplateHandler.create(thymeleaf);

		ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
		resolver.setSuffix(".html");
		resolver.setCacheable(false);
		resolver.setTemplateMode("HTML5");
		resolver.setCharacterEncoding("utf-8");
		thymeleaf.getThymeleafTemplateEngine().setTemplateResolver(resolver);

		eb = vertx.eventBus();

		Router router = Router.router(vertx);

		SockJSHandler sockJSHandler = SockJSHandler.create(vertx);

		if (config().getBoolean("enable-eb", false)) {
			router.route("/acj/eventbus/*").handler(sockJSHandler);
		}

		StaticHandler staticfiles = StaticHandler.create().setCachingEnabled(false).setWebRoot("static");
		router.route("/acj/static/*").handler(staticfiles);
		router.route("/acj").pathRegex("\\/.+\\.json").handler(staticfiles);
		router.route("/acj/index").handler(BodyHandler.create());
		router.route("/acj/index").handler(ctx -> this.index(sockJSHandler, ctx));
		router.route("/acj/manage/*").handler(BodyHandler.create());
		router.route("/acj/manage/data/cached/:username/get").produces("application/json")
				.handler(ctx -> this.getCachedData(ctx));
		router.route("/acj/manage/cleandata").produces("application/json").handler(ctx -> this.cleanData(ctx));
		router.route("/acj/manage/data/rules/get").produces("application/json").handler(ctx -> this.cleanRules(ctx));
		router.route("/acj/manage/data/rules/save").consumes("application/json").produces("application/json")
				.handler(ctx -> this.saveRules(ctx));
		router.route("/acj/rules/refresh").produces("application/json").handler(ctx -> this.refresh(ctx));

		// 未绑定订阅请求处理
		router.route("/acj/eventbus/*").handler(ctx -> {
			ctx.response().setStatusCode(404).end("{}");
		});

		router.route("/acj").pathRegex("\\/[^\\.]*").handler(templatehandler);

		HttpServerOptions option = new HttpServerOptions();
		option.setCompressionSupported(true);

		vertx.createHttpServer(option).requestHandler(router::accept).listen(8080, http -> {
			if (http.succeeded()) {
				startFuture.complete();
				System.out.println("HTTP server started on http://localhost:8080");
			} else {
				startFuture.fail(http.cause());
			}
		});
	}

	public static String getShortContent(String origin) {
		return origin.length() > 512 ? origin.substring(0, 512) : origin;
	}

	private void saveRules(RoutingContext ctx) {
		JsonObject rule = ctx.getBodyAsJson();

		if (rule != null && !rule.containsKey("_id") && !rule.containsKey("rule-shouldclean")) {
			rule.put("rule-shouldclean",
					"function shouldclean(datasource) \n" + "{\n" + "  var result = {};\n"
							+ "  // filter source code here start\n" + "  var input = JSON.parse(datasource);\n"
							+ "  \n" + "  // filter source code here end\n" + "  return false;\n" + "}");
		}

		if (rule != null && !rule.containsKey("_id") && !rule.containsKey("rule-clean")) {
			rule.put("rule-clean", "function clean(datasource) \n" + "{\n" + "  var result = {};\n"
					+ "  // filter source code here start\n" + "  var input = JSON.parse(datasource);\n" + "  \n"
					+ "  // filter source code here end\n" + "  return JSON.stringify(result);\n" + "}");
		}

		if (rule.containsKey("_id")) {
			JsonObject updaterule = rule.copy();
			updaterule.remove("_id");

			mongodb.findOneAndUpdate("acj_clean_rules", new JsonObject().put("_id", rule.getString("_id")),
					new JsonObject().put("$set", updaterule), save -> {
						if (save.succeeded()) {
							ctx.response().putHeader("Content-Type", "application/json; charset=utf-8")
									.end(rule.encode());
						} else {
							save.cause().printStackTrace();

							ctx.response().putHeader("Content-Type", "application/json; charset=utf-8")
									.end(new JsonObject().put("errcode", -3).put("errmsg", save.cause().getMessage())
											.encode());
						}
					});
		} else {
			mongodb.save("acj_clean_rules", rule, save -> {
				if (save.succeeded()) {
					ctx.response().putHeader("Content-Type", "application/json; charset=utf-8").end(rule.encode());
				} else {
					save.cause().printStackTrace();

					ctx.response().putHeader("Content-Type", "application/json; charset=utf-8")
							.end(new JsonObject().put("errcode", -3).put("errmsg", save.cause().getMessage()).encode());
				}
			});
		}
	}

	private void cleanRules(RoutingContext ctx) {

		mongodb.find("acj_clean_rules", new JsonObject(), find -> {
			if (find.succeeded()) {
				List<JsonObject> rules = find.result();

				if (rules == null || rules.size() < 1) {
					rules = new ArrayList<JsonObject>();

					rules.add(new JsonObject().put("rule-id", "default").put("rule-name", "默认规则")
							.put("rule-shouldclean",
									"function shouldclean(datasource) \n" + "{\n" + "  var result = {};\n"
											+ "  // filter source code here start\n" + "  \n"
											+ "  // filter source code here end\n" + "  return false;\n" + "}")
							.put("rule-clean",
									"function clean(datasource) \n" + "{\n" + "  var result = {};\n"
											+ "  // filter source code here start\n" + "  \n"
											+ "  // filter source code here end\n" + "  return result;\n" + "}")
							.put("rule-actived", false));
				}

				ctx.response().putHeader("Content-Type", "application/json; charset=utf-8")
						.end(new JsonObject().put("rules", rules).encode());
			} else {

				List<JsonObject> rules = new ArrayList<JsonObject>();

				rules.add(new JsonObject().put("rule-id", "default").put("rule-name", "默认规则")
						.put("rule-shouldclean", "function shouldclean(datasource) \n" + "{\n" + "  var result = {};\n"
								+ "  // filter source code here start\n" + "  var input = JSON.parse(datasource);\n"
								+ "  \n" + "  // filter source code here end\n" + "  return false;\n" + "}")
						.put("rule-clean", "function clean(datasource) \n" + "{\n" + "  var result = {};\n"
								+ "  // filter source code here start\n" + "  var input = JSON.parse(datasource);\n"
								+ "  \n" + "  // filter source code here end\n" + "  return result;\n" + "}")
						.put("rule-actived", false));

				ctx.response().putHeader("Content-Type", "application/json; charset=utf-8")
						.end(new JsonObject().put("rules", rules).encode());
			}
		});
	}

	private void connectStompServer() {
		bridge.start(config().getString("stomp.server.host", "sa-amq"), config().getInteger("stomp.server.port", 5672),
				res -> {
					if (res.failed()) {
						res.cause().printStackTrace();
						if (!config().getBoolean("debug", true)) {
							connectStompServer();
						}
					} else {
						subscribeTrigger(config().getString("amq.app.id", "acj"));
					}
				});

	}

	private void subscribeTrigger(String trigger) {
		MessageConsumer<JsonObject> consumer = bridge.createConsumer(trigger);
		System.out.println("Consumer " + trigger + " subscribed.");
		consumer.handler(vertxMsg -> this.process(trigger, vertxMsg));
	}

	private void process(String consumer, Message<JsonObject> received) {
		System.out.println("Consumer " + consumer + " received [" + getShortContent(received.body().encode()) + "]");
		JsonObject data = received.body().getJsonObject("body");

		String next = data.getJsonObject("context").getString("next");

		String ruleId = data.getJsonObject("context").getString("ruleid", "");
		JsonObject datasource = null;

		try {
			datasource = data.getJsonObject("context").getJsonObject("datasource", new JsonObject());
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			if (datasource == null) {
				datasource = new JsonObject();
			}
		}

		try {
			autoclean(consumer, ruleId, datasource, next, 1);
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	private Map<String, JsonObject> cachedRules = new LinkedHashMap<String, JsonObject>();
	private Map<String, Object> cachedLogicals = new LinkedHashMap<String, Object>();

	private void refresh(RoutingContext ctx) {
		ScriptEngineManager factory = new ScriptEngineManager();

		mongodb.find("acj_clean_rules", new JsonObject().put("rule-actived", true), find -> {
			if (find.succeeded()) {
				List<JsonObject> rules = find.result();

				if (rules != null && rules.size() > 0) {
					cachedRules.clear();
					
					for (JsonObject rule : rules) {
						String ruleId = rule.getString("rule-id", String.valueOf(rule.hashCode()));
						
						cachedRules.put(ruleId, rule);
						ScriptEngine logicalengine = factory.getEngineByName("JavaScript");
						ScriptEngine shouldlogicalengine = factory.getEngineByName("JavaScript");

						String shouldlogical = rule.getString("rule-shouldclean");
						String logical = rule.getString("rule-clean");

						try {
							shouldlogicalengine.eval(shouldlogical);
							logicalengine.eval(logical);
						} catch (Exception e) {
							System.out.println(ruleId + " scripts:");
							System.out.println(shouldlogical);
							System.out.println(logical);
							e.printStackTrace();
							shouldlogicalengine = null;
							logicalengine = null;
						}

						cachedLogicals.put(ruleId + "-shouldlogical", shouldlogicalengine);
						cachedLogicals.put(ruleId + "-logical", logicalengine);
					}
				}
			}
		});

		if (ctx != null) {
			ctx.response().putHeader("Content-Type", "application/json").end("{}");
		}
	}

	private void autoclean(String consumer, String targetrule, JsonObject datasource, String nextTask, Integer retry) {
		JsonObject nexto = new JsonObject();

		try {
			if ("".equals(targetrule) || "*".equals(targetrule) || !cachedRules.containsKey(targetrule)) {
				List<Future<JsonObject>> logicalFutures = new LinkedList<Future<JsonObject>>();
				
				for (Map.Entry<String, JsonObject> ruleentry : cachedRules.entrySet()) {
					Future<JsonObject> logicalFuture = Future.future();
					logicalFutures.add(logicalFuture);
					
					vertx.executeBlocking(blockingHandler -> {
						JsonObject rule = ruleentry.getValue();
						
						String rulename = rule.getString("rule-name");
						String ruleid = rule.getString("rule-id");
						String shouldlogical = rule.getString("rule-shouldclean");
						String logical = rule.getString("rule-clean");

						ScriptEngine logicalengine = (ScriptEngine) cachedLogicals.get(ruleid + "-logical");
						ScriptEngine shouldlogicalengine = (ScriptEngine) cachedLogicals.get(ruleid + "-shouldlogical");

						if (shouldclean(shouldlogicalengine, datasource)) {
							System.out.println(
									"Rule " + rulename + "[" + ruleid + "] auto clean data " + datasource.size());
							String output = clean(logicalengine, datasource);
							System.out.println("Rule " + rulename + "[" + ruleid + "] auto clean result " + output);
							JsonObject cleaned = StringUtils.isEmpty(output) ? new JsonObject()
									: (output.startsWith("{") ? new JsonObject(output)
											: new JsonObject().put(ruleid, new JsonArray(output)));
							blockingHandler.complete(cleaned);
						} else {
							//System.out.println("Rule " + rulename + "[" + ruleid + "] auto clean skipped.");
							blockingHandler.complete(new JsonObject());
						}
					}, logicalFuture.completer());
				}
				
				CompositeFuture.all(Arrays.asList(logicalFutures.toArray(new Future[logicalFutures.size()])))
				.map(v -> logicalFutures.stream().map(Future::result).collect(Collectors.toList()))
				.setHandler(handler -> {
					if (handler.succeeded()) {
						List<JsonObject> results = handler.result();
						
						for (JsonObject res : results) {
							nexto.mergeIn(res);
						}

						JsonObject nextctx = new JsonObject().put("context", new JsonObject().put("cleaned", nexto));

						MessageProducer<JsonObject> producer = bridge.createProducer(nextTask);
						producer.send(new JsonObject().put("body", nextctx));
						System.out.println("Consumer " + consumer + " send to [" + nextTask + "] result [" + nextctx.size() + "]");
					} else {
						handler.cause().printStackTrace();
						
						JsonObject nextctx = new JsonObject().put("context", new JsonObject().put("cleaned", nexto));

						MessageProducer<JsonObject> producer = bridge.createProducer(nextTask);
						producer.send(new JsonObject().put("body", nextctx));
						System.out.println("Consumer " + consumer + " send to [" + nextTask + "] result [" + nextctx.size() + "]");
					}
				});
			} else {
				JsonObject rule = cachedRules.get(targetrule);
				
				String rulename = rule.getString("rule-name");
				String ruleid = rule.getString("rule-id");
				String shouldlogical = rule.getString("rule-shouldclean");
				String logical = rule.getString("rule-clean");

				ScriptEngine logicalengine = (ScriptEngine) cachedLogicals.get(ruleid + "-logical");
				ScriptEngine shouldlogicalengine = (ScriptEngine) cachedLogicals.get(ruleid + "-shouldlogical");

				if (shouldclean(shouldlogicalengine, datasource)) {
					System.out.println(
							"Rule " + rulename + "[" + ruleid + "] auto clean data " + datasource.size());
					String output = clean(logicalengine, datasource);
					System.out.println("Rule " + rulename + "[" + ruleid + "] auto clean result " + output);
					JsonObject cleaned = StringUtils.isEmpty(output) ? new JsonObject()
							: (output.startsWith("{") ? new JsonObject(output)
									: new JsonObject().put(ruleid, new JsonArray(output)));
					nexto.mergeIn(cleaned);
				} else {
					//System.out.println("Rule " + rulename + "[" + ruleid + "] auto clean skipped.");
				}

				JsonObject nextctx = new JsonObject().put("context", new JsonObject().put("cleaned", nexto));

				MessageProducer<JsonObject> producer = bridge.createProducer(nextTask);
				producer.send(new JsonObject().put("body", nextctx));
				System.out.println("Consumer " + consumer + " send to [" + nextTask + "] result [" + nextctx.size() + "]");
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	private List<String> onlineclients = new ArrayList<String>();

	private void sockjsclientin(Message<JsonObject> message) {
		JsonObject received = message.body();

		if (received.containsKey("type") && "broadcast".equals(received.getString("type").toLowerCase())) {
//			System.out.println(received.size());
			String username = received.getString("username");

			String base64key = Base64.encodeBase64URLSafeString(username.getBytes());

			String dataTo = received.getJsonObject("payload").getString("data-to");
			received.put("data-src", "acj");
			received.put("data-direction", "sender");
			MessageProducer<JsonObject> producer = bridge.createProducer("acz");
			producer.send(new JsonObject().put("body", new JsonObject().put("op", "send")
					.put("address", dataTo + "." + base64key).put("payload", received)));
		}

		if (received.containsKey("type") && "online".equals(received.getString("type").toLowerCase())) {
			//System.out.println(received.size());
			String username = received.getString("username");

			String base64key = Base64.encodeBase64URLSafeString(username.getBytes());

			if (!onlineclients.contains(base64key)) {
				onlineclients.add(base64key);

				MessageConsumer<JsonObject> consumer = bridge.createConsumer("acj." + base64key);
				consumer.handler(broadcast -> this.amqpclientin(base64key, broadcast));
			}

			MessageProducer<JsonObject> subscribe = bridge.createProducer("acz");
			subscribe.send(new JsonObject().put("body", new JsonObject().put("op", "subscribe")
					.put("address", base64key).put("reply_address", "acj." + base64key)));

			received.put("data-src", "acj");
			received.put("data-direction", "receiver, sender");
			MessageProducer<JsonObject> producer = bridge.createProducer("acz");
			producer.send(new JsonObject().put("body",
					new JsonObject().put("op", "publish").put("address", base64key).put("payload", received)));
		}
	}

	private void amqpclientin(String userbase64, Message<JsonObject> message) {
		JsonObject received = message.body().getJsonObject("body");
		//System.out.println(received.size());

		if (!received.containsKey("data-src")
				|| (received.containsKey("data-src") && !"acj".equals(received.getString("data-src")))) {
			received.put("direction", "input");
			eb.send("sockjsclientout@" + userbase64, received);
		}
	}

	// 判断输入数据是否需要当前规则清洗
	private boolean shouldclean(String logical, JsonObject datasource) {
		ScriptEngineManager factory = new ScriptEngineManager();
		ScriptEngine engine = factory.getEngineByName("JavaScript");

		Object res = null;
		Boolean output = Boolean.FALSE;

		try {
			engine.put("datasource", datasource.encode());
			engine.eval(logical);
			Invocable jsInvoke = (Invocable) engine;
			res = jsInvoke.invokeFunction("shouldclean", new Object[] { datasource });
			output = (Boolean) res;
			//System.out.println(res.getClass().getName());
		} catch (ScriptException | NoSuchMethodException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			if (!(output instanceof Boolean)) {
				output = Boolean.FALSE;
			}
		}

		return output;
	}

	// 判断输入数据是否需要当前规则清洗
	private boolean shouldclean(ScriptEngine logical, JsonObject datasource) {

		Object res = null;
		Boolean output = Boolean.FALSE;

		try {
			logical.put("datasource", datasource.encode());
			Invocable jsInvoke = (Invocable) logical;
			res = jsInvoke.invokeFunction("shouldclean", new Object[] { datasource });
			output = (Boolean) res;
			//System.out.println(res.getClass().getName());
		} catch (ScriptException | NoSuchMethodException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			if (!(output instanceof Boolean)) {
				output = Boolean.FALSE;
			}
		}

		return output;
	}

	// 使用当前规则清洗
	private String clean(String logical, JsonObject datasource) {
		ScriptEngineManager factory = new ScriptEngineManager();
		ScriptEngine engine = factory.getEngineByName("JavaScript");

		Object res = null;
		String output = "";

		try {
			engine.put("datasource", datasource.encode());
			engine.eval(logical);
			Invocable jsInvoke = (Invocable) engine;
			res = jsInvoke.invokeFunction("clean", new Object[] { datasource });
			output = (String) res;
			//System.out.println(res.getClass().getName());
		} catch (ScriptException | NoSuchMethodException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return output;
	}

	// 使用当前规则清洗
	private String clean(ScriptEngine logical, JsonObject datasource) {
		Object res = null;
		String output = "";

		try {
			logical.put("datasource", datasource.encode());
			Invocable jsInvoke = (Invocable) logical;
			res = jsInvoke.invokeFunction("clean", new Object[] { datasource });
			output = (String) res;
			//System.out.println(res.getClass().getName());
		} catch (ScriptException | NoSuchMethodException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return output;
	}

	private void cleanData(RoutingContext ctx) {
		System.out.println(ctx.getBodyAsString());
		JsonObject params = ctx.getBodyAsJson();
		JsonObject datasource = params.getJsonObject("data-src");
		String logical = params.getString("logical");
		String creator = params.getString("username");

		mongodb.save("acj_last_logical", new JsonObject().put("logical", logical).put("datasource", datasource)
				.put("creator", creator).put("createdt", System.currentTimeMillis()), ar -> {
					if (ar.failed()) {
						ar.cause().printStackTrace();
					}
				});

		String output = clean(logical, datasource);

		JsonObject cacheData = new JsonObject().put("direction", "output")
				.put("data-title", datasource.getString("data-title")).put("data-src", "acj")
				.put("data-from", datasource.getString("data-from"))
				.put("data", (output.startsWith("{") ? new JsonObject(output)
						: new JsonArray(output)))
				.put("createdt", System.currentTimeMillis());

		if (!StringUtils.isEmpty(creator)) {
			cacheData.put("creator", creator);
		}

		mongodb.insert("acj_data_cached", cacheData, insert -> {
			if (insert.succeeded()) {
				System.out.println("data cached.");
			} else {
				insert.cause().printStackTrace();
			}
		});
		ctx.response().end(cacheData.encode());

	}

	private void getCachedData(RoutingContext ctx) {
		String username = ctx.request().getParam("username");
		JsonObject query = new JsonObject().put("$or", new JsonArray()
				.add(new JsonObject().put("$and",
						new JsonArray().add(new JsonObject().put("creator", new JsonObject().put("$exists", true)))
								.add(new JsonObject().put("creator", new JsonObject().put("$eq", username)))))
				.add(new JsonObject().put("creator", new JsonObject().put("$exists", false))));

		mongodb.find("acj_data_cached", query, find -> {
			if (find.succeeded()) {
				List<JsonObject> cacheddatas = find.result();

				ctx.response().end(new JsonArray(cacheddatas).encode());
			} else {
				find.cause().printStackTrace();
				ctx.response().end("{}");
			}
		});
	}

	private void index(SockJSHandler sockJSHandler, RoutingContext ctx) {
		Cookie ctoken = ctx.getCookie("authorized_user");
		Cookie copenid = ctx.getCookie("authorized_openid");

		String token = ctoken == null ? "" : ctoken.getValue();
		String openid = copenid == null ? "" : copenid.getValue();

		Map<String, String> user = new HashMap();
		user.put("name", "席理加");

		if (token != null && !"".equals(token) && openid != null && !"".equals(openid)) {
			client.head(8080, "sa-aba", "/aba/user/" + openid + "/info").send(handler -> {
				if (handler.succeeded()) {
					JsonObject result = handler.result().bodyAsJsonObject();

					Map user1 = result.getJsonObject("data").mapTo(HashMap.class);

					user.putAll(user1);

				} else {
					handler.cause().printStackTrace();
				}

				jsbridge(user.get("name"), sockJSHandler);
				user.put("base64", Base64.encodeBase64URLSafeString(user.get("name").getBytes()));
				ctx.put("userinfo", user);
				ctx.next();
			});
		} else {
			jsbridge(user.get("name"), sockJSHandler);
			user.put("base64", Base64.encodeBase64URLSafeString(user.get("name").getBytes()));
			ctx.put("userinfo", user);
			ctx.next();
		}
	}

	private BridgeOptions options = new BridgeOptions();

	private void jsbridge(String username, SockJSHandler sockJSHandler) {
		String userbase64 = Base64.encodeBase64URLSafeString(username.getBytes());

		MessageConsumer<JsonObject> consumer = eb.consumer("sockjsclientin@" + userbase64);
		consumer.handler(message -> this.sockjsclientin(message));

		PermittedOptions inboundPermitted = new PermittedOptions().setAddress("sockjsclientin@" + userbase64);
		PermittedOptions outboundPermitted = new PermittedOptions().setAddress("sockjsclientout@" + userbase64);

		options.addInboundPermitted(inboundPermitted).addOutboundPermitted(outboundPermitted);

		sockJSHandler.bridge(options, be -> {
			if (be.type() == BridgeEventType.PUBLISH || be.type() == BridgeEventType.SEND) {
				// Add some headers
				JsonObject headers = new JsonObject().put("header1", "val").put("header2", "val2");
				JsonObject rawMessage = be.getRawMessage();
				rawMessage.put("headers", headers);
				be.setRawMessage(rawMessage);
			}
			be.complete(true);
		});

	}
}
