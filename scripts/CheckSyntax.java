import javax.tools.*;
import com.sun.source.util.JavacTask;
import java.nio.file.*;
import java.util.*;

public class CheckSyntax {
    public static void main(String[] args) throws Exception {
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
        try(StandardJavaFileManager manager=compiler.getStandardFileManager(diagnostics,null,null)) {
            List<java.io.File> files=new ArrayList<>();
            try(var paths=Files.walk(Path.of(args[0]))) { paths.filter(p->p.toString().endsWith(".java")).forEach(p->files.add(p.toFile())); }
            JavacTask task=(JavacTask)compiler.getTask(null,manager,diagnostics,List.of("-proc:none","-source","17"),null,manager.getJavaFileObjectsFromFiles(files));
            for(var ignored:task.parse()) {}
            for(var d:diagnostics.getDiagnostics())if(d.getKind()==Diagnostic.Kind.ERROR)throw new AssertionError(d.toString());
            System.out.println("Java syntax parsed: "+files.size()+" files. This does not type-check Android APIs.");
        }
    }
}
