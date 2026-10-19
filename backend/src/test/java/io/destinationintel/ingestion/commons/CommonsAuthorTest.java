package io.destinationintel.ingestion.commons;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CommonsAuthorTest {

    @Test
    void findsTheFlickrPhotographerOfATransferredFile() {
        // The description page of File:UB City.jpg (no Information template, Flickr review present).
        String page = """
                == {{int:filedesc}} ==
                https://www.flickr.com/photos/soulflow/1410390525/

                == {{int:license-header}} ==
                {{cc-by-sa-2.0}}{{User:FlickreviewR/reviewed-pass-change|andreina_photos|https://www.flickr.com/photos/7342616@N02/1410390525|13:02, 16 October 2008 (UTC)|cc-by-sa-2.0|cc-by-2.0}}
                """;
        assertThat(PlaceImageHandler.authorFromWikitextContent(page)).isEqualTo("andreina_photos (Flickr)");
    }

    @Test
    void prefersTheInformationTemplateAuthor() {
        String page = """
                {{Information
                |description=Palace at night
                |author=[[User:Example|Example Photographer]]
                |source={{own}}
                }}
                """;
        assertThat(PlaceImageHandler.authorFromWikitextContent(page)).isEqualTo("Example Photographer");
        assertThat(PlaceImageHandler.authorFromWikitextContent("{{cc-by-4.0}}")).isNull();
    }
}
