package org.koitharu.kotatsu;

import org.junit.Test;
import org.koitharu.kotatsu.parsers.MangaParserFactoryKt;
import org.koitharu.kotatsu.parsers.model.MangaParserSource;

public class SourceTestJava {
    @Test
    public void testSources() {
        org.koitharu.kotatsu.parsers.MangaLoaderContext context = (org.koitharu.kotatsu.parsers.MangaLoaderContext) java.lang.reflect.Proxy.newProxyInstance(
            org.koitharu.kotatsu.parsers.MangaLoaderContext.class.getClassLoader(),
            new Class<?>[] { org.koitharu.kotatsu.parsers.MangaLoaderContext.class },
            (proxy, method, args) -> null
        );
        for (MangaParserSource source : MangaParserSource.values()) {
            if (source.isBroken()) continue;
            try {
                org.koitharu.kotatsu.parsers.MangaParser parser = MangaParserFactoryKt.newParser(source, context);
                String domain = parser.getDomain();
                if (domain.startsWith("http://")) {
                    System.out.println("HTTP_SOURCE=" + source.name());
                }
            } catch (Exception e) {
            }
        }
    }
}
