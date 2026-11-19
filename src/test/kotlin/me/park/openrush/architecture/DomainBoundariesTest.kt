package me.park.openrush.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertTrue

class DomainBoundariesTest {
    private val root = Path.of("src/main/kotlin/me/park/openrush")
    private val modules = setOf("concert", "inventory", "quota", "reservation", "payment", "queue")
    private val sources = Files.walk(root).use { paths ->
        paths.filter { it.toString().endsWith(".kt") }.toList().associateWith { it.readText() }
    }
    private val imports = Regex("(?m)^import me\\.park\\.openrush\\.([a-z]+)\\.([^\\n]+)")

    @Test
    fun `도메인 간 호출은 입력 포트를 사용하고 다른 도메인의 엔티티와 구현체를 참조하지 않는다`() {
        val entities = sources.mapNotNull { (_, source) ->
            if (!source.contains("@Entity")) return@mapNotNull null
            val pkg = Regex("(?m)^package ([^\\n]+)").find(source)!!.groupValues[1]
            val name = Regex("(?m)^class (\\w+)").find(source)!!.groupValues[1]
            "$pkg.$name"
        }.toSet()
        val violations = mutableListOf<String>()
        sources.forEach { (file, source) ->
            val relative = root.relativize(file)
            val owner = relative.getName(0).toString()
            if (owner !in modules) return@forEach
            imports.findAll(source).forEach dependency@{ match ->
                val target = match.groupValues[1]
                val member = match.groupValues[2]
                if (relative.getName(1).toString() == "domain" &&
                    (target == "config" || (target == "support" && !member.startsWith("persistence.")))) {
                    violations += "$relative -> ${match.value}"
                }
                if (target !in modules || target == owner) return@dependency
                val publicException = member.startsWith("application.exception.") && member.endsWith("Exception")
                if (member.startsWith("application.") && !member.startsWith("application.provided.") && !publicException) {
                    violations += "$relative -> ${match.value}"
                }
                if (member.startsWith("adapter.") || "me.park.openrush.$target.$member" in entities) {
                    violations += "$relative -> ${match.value}"
                }
                if (relative.getName(1).toString() == "domain") violations += "$relative -> ${match.value}"
            }
        }
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    @Test
    fun `PG 연동은 예약 도메인과 저장소에 의존하지 않는다`() {
        val violations = sources.filter { (file, source) ->
            root.relativize(file).getName(0).toString() == "payment" &&
                (source.contains("@Entity") || imports.findAll(source).any { it.groupValues[1] == "reservation" })
        }
        assertTrue(violations.isEmpty(), violations.keys.joinToString("\n"))
    }

    @Test
    fun `업무 규칙은 어댑터와 application에 의존하지 않는다`() {
        val violations = sources.filter { (file, source) ->
            val path = root.relativize(file)
            path.getName(0).toString() in modules && path.getName(1).toString() == "domain" &&
                imports.findAll(source).any { it.groupValues[2].startsWith("application.") || it.groupValues[2].startsWith("adapter.") }
        }
        assertTrue(violations.isEmpty(), violations.keys.joinToString("\n"))
    }

    @Test
    fun `공용 영속성 기반 코드는 업무 도메인과 웹 설정에 의존하지 않는다`() {
        val violations = sources.filter { (file, source) ->
            root.relativize(file).startsWith(Path.of("support", "persistence")) &&
                imports.findAll(source).any {
                    it.groupValues[1] in modules || it.groupValues[1] == "config" ||
                        (it.groupValues[1] == "support" && !it.groupValues[2].startsWith("persistence."))
                }
        }
        assertTrue(violations.isEmpty(), violations.keys.joinToString("\n"))
    }
}
