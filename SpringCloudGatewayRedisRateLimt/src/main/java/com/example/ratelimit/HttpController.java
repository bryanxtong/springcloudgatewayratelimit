package com.example.ratelimit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping
public class HttpController {

    @Autowired
    private WebClient.Builder webclientBuilder;

    private WebClient webClient;

    public HttpController(WebClient.Builder webclientBuilder) {
        this.webclientBuilder = webclientBuilder.baseUrl("http://httpbin.org:80");
        webClient = this.webclientBuilder.build();
    }

    @GetMapping("/ip")
    public Mono<String> getHttpBin(){
        //System.out.println("Current Thread Name: "+Thread.currentThread().getName());
        Mono<String> stringMono = webclientBuilder.build().get().uri("/ip").retrieve().bodyToMono(String.class);
        return stringMono;

    }
}
