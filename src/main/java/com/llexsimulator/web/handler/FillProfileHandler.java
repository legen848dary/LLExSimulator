package com.llexsimulator.web.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llexsimulator.fill.FillProfileManager;
import com.llexsimulator.web.dto.FillProfileDto;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/** CRUD REST handler for fill-behavior profiles. */
public final class FillProfileHandler {

    private final FillProfileManager profileManager;
    private final ObjectMapper        mapper;

    public FillProfileHandler(FillProfileManager profileManager, ObjectMapper mapper) {
        this.profileManager = profileManager;
        this.mapper         = mapper;
    }

    public Handler<RoutingContext> list() {
        return ctx -> {
            try {
                ctx.response()
                   .putHeader("Content-Type", "application/json")
                   .end(mapper.writeValueAsString(profileManager.getAllProfiles()));
            } catch (Exception e) {
                ctx.fail(500, e);
            }
        };
    }

    public Handler<RoutingContext> createOrUpdate() {
        return ctx -> ctx.request().bodyHandler(body -> {
            try {
                FillProfileDto dto = mapper.readValue(body.getBytes(), FillProfileDto.class);
                profileManager.createOrUpdate(dto);
                ctx.response().setStatusCode(201)
                   .putHeader("Content-Type", "application/json")
                   .end("{\"status\":\"saved\",\"name\":\"" + dto.name + "\"}");
            } catch (Exception e) {
                ctx.fail(400, e);
            }
        });
    }

    public Handler<RoutingContext> activate() {
        return ctx -> {
            try {
                String name = ctx.pathParam("name");
                profileManager.activate(name);
                ctx.response()
                   .putHeader("Content-Type", "application/json")
                   .end("{\"status\":\"activated\",\"name\":\"" + name + "\"}");
            } catch (IllegalArgumentException e) {
                ctx.response().setStatusCode(404).end("{\"error\":\"" + e.getMessage() + "\"}");
            } catch (Exception e) {
                ctx.fail(500, e);
            }
        };
    }

    public Handler<RoutingContext> delete() {
        return ctx -> {
            String name = ctx.pathParam("name");
            boolean deleted = profileManager.delete(name);
            ctx.response()
               .putHeader("Content-Type", "application/json")
               .setStatusCode(deleted ? 200 : 404)
               .end("{\"deleted\":" + deleted + "}");
        };
    }
}

