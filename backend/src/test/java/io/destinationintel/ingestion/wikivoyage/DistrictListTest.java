package io.destinationintel.ingestion.wikivoyage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DistrictListTest {

    /** Shape of Wikivoyage's "Delhi" districts section: a commented-out parameter inside the region list. */
    static final String DELHI = """
            Intro text.

            ==Districts==
            {{mapframe|28.641|77.093|zoom=10|width=400|height=420}}
            {{Mapshapes|Q271195}} <!-- Metro -->

            For travel purposes, the city is divided into the following eight districts.

            {{Regionlist
            <!-- | regionmap=Delhi map wikivoyage.png
            | regionInteractiveMap=map1 -->

            | region1name=[[Delhi/Central|Central Delhi]]
            | region1color={{StdColor|t1}}
            | region1items=Karol Bagh, Paharganj
            | region1description=Backpacker district.

            | region2name=[[Delhi/East|East Delhi]]
            | region2color={{StdColor|t2}}
            | region2items=Akshardham, Mayur Vihar
            | region2description=Eastern suburbs.

            | region3name=[[New Delhi]]
            | region3items=[[New Delhi/Central|Central]], [[New Delhi/North|North]]
            | region3description=The British capital.
            }}

            ==Understand==
            Text.
            """;

    @Test
    void readsDistrictsEvenWithACommentedOutParameter() {
        var article = WikivoyageArticleParser.parse(DELHI);
        assertThat(article.regions()).extracting(WikivoyageArticleParser.Region::linkTarget)
                .contains("Delhi/Central", "Delhi/East");
    }
}
