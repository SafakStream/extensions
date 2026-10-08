package com.safakstream.dizipal

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchItem(
    @JsonProperty("title") val title: String = "",
    @JsonProperty("slug") val slug: String = "",
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("type") val type: String = "movie"
)
