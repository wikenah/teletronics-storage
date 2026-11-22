package com.teletronics.storage.presentation.http

import com.teletronics.storage.presentation.http.config.TomcatContinueTimingCustomizer
import com.teletronics.storage.presentation.http.problem.ProblemDetailsAdvice
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Import

/**
 * Only the HTTP layer: Tomcat, Spring MVC, our controllers, error mapping and connector setup.
 * The file service is faked by each spec; nothing from infrastructure is loaded.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(excludeName = [
        "org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration",
        "org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration",
        "org.springframework.boot.data.mongodb.autoconfigure.DataMongoRepositoriesAutoConfiguration",
])
@Import([FilesController, DownloadController, ProblemDetailsAdvice, TomcatContinueTimingCustomizer])
class HttpLayerTestApplication {
}
