package net.darkhax.curseforgegradle;

import groovy.lang.Closure;
import com.google.common.collect.ImmutableList;
import net.darkhax.curseforgegradle.api.versions.GameVersions;
import net.darkhax.curseforgegradle.versionTypes.*;
import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Task;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFile;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.TaskAction;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * A Gradle task that can publish multiple files to CurseForge. A project can define any number of these tasks, and any
 * given task can be responsible for publishing any number of files to any number of projects.
 */
public abstract class TaskPublishCurseForge extends DefaultTask {

    /**
     * The display name of the project that defined this task.
     */
    private final String projectDisplayName = this.getProject().getDisplayName();

    /**
     * An internal logger instance used to print warnings, errors, and debug information. The logger name includes the
     * name of the project that defined this task and the name of the task.
     */
    private final Logger log;

    /**
     * An internal object that fetches and holds the valid game versions for the current game. This will be null until
     * the {@link #initialize()} step has occurred.
     */
    @Nullable
    private GameVersions validGameVersions;

    /**
     * Handles the automatic discovery of game version tags from variables in the Gradle environment. If this is not
     * disabled detected versions will be applied in {@link #initialize()}.
     */
    private final VersionDetector versionDetector;

    /**
     * An internal list of all top-level artifacts that this task should publish. New artifacts are added to this list
     * by using {@link #upload(Object, Object)} during the task configuration phase. These artifacts will be published
     * to CurseForge during the {@link #publish()} step.
     */
    private final List<UploadArtifact> uploadArtifacts = new LinkedList<>();

    /**
     * The version type provider to use for this task. This is used to determine the valid version types for the game.
     * By default {@link ModMinecraftVersionTypeProvider} is used which supports all .
     */
    private final Set<VersionTypeProvider> versionTypeProviders = new HashSet<>(ImmutableList.of(
            new ModMinecraftVersionTypeProvider(),
            new EnvironmentVersionTypeProvider(),
            new JavaVersionTypeProvider(),
            new ModloaderVersionTypeProvider()
    ));

    /**
     * The game specific API endpoint. This is used to retrieve lists of valid versions for a game and to help files get
     * uploaded to the right game.
     */
    public Object apiEndpoint = "https://minecraft.curseforge.com";

    /**
     * The API token used to publish files on your behalf. This token must have the correct project permissions for the
     * files to be published. These tokens can be generated here: https://legacy.curseforge.com/account/api-tokens
     */
    public Object apiToken;

    /**
     * Determines if publishing should actually happen. Set this to {@code true} to log the json request instead of sending it to curse's servers.
     */
    public boolean debugMode;

    /**
     * This task should not be constructed manually. It will be constructed dynamically by Gradle when a user defines
     * the task. Code inside the constructor will be executed before the user configuration.
     */
    public TaskPublishCurseForge() {

        this.log = Logging.getLogger("CurseForgeGradle/" + projectDisplayName + "/" + this.getName());
        this.versionDetector = new VersionDetector(this.getProject(), this.log);

        // Ensure publishing takes place after the build task has completed. This is required
        // in some environments such as those with parallel task execution enabled.
        final Task buildTask = this.getProject().getTasks().findByName("build");

        if (buildTask != null) {

            this.mustRunAfter(this.getProject().getTasks().getByName("build"));
        }
    }

    @Nested
    public List<UploadArtifact> getUploadArtifacts() {
        return ImmutableList.copyOf(uploadArtifacts);
    }

    @Inject
    public abstract ObjectFactory getObjectFactory();

    /**
     * Creates a new main level artifact that the plugin will attempt to publish during the {@link #publish()} step.
     * This method requires the minimum amount of information to define an artifact. Further configuration including
     * defining additional sub files can be done by modifying the returned artifact instance.
     *
     * @param projectId The CurseForge project ID to publish this artifact to.
     * @param toUpload  The artifact to upload when this artifact is published. This can accept files, archive tasks,
     *                  and several other types of files. The resolution of this is handled by {@link FileCollection}.
     * @return An object that represents the artifact being published. This can be used to perform additional
     * configuration such as defining a changelog.
     */
    public UploadArtifact upload(Object projectId, Object toUpload) {

        final UploadArtifact artifact = new UploadArtifact(toUpload, parseLong(projectId), getObjectFactory(), this.log, null);
        this.uploadArtifacts.add(artifact);
        return artifact;
    }

    /**
     * Creates a new main level artifact that the plugin will attempt to publish during the {@link #publish()} step.
     * This method requires the minimum amount of information to define an artifact. Further configuration including
     * defining additional sub files can be done by modifying the returned artifact instance.
     *
     * @param projectId The CurseForge project ID to publish this artifact to.
     * @param toUpload  The artifact to upload when this artifact is published. This can accept files, archive tasks,
     *                  and several other types of files. The resolution of this is handled by {@link FileCollection}.
     * @param action    The {@link Action} to apply before returning the artifact.
     * @return An object that represents the artifact being published. This can be used to perform additional
     * configuration such as defining a changelog.
     */
    public UploadArtifact upload(Object projectId, Object toUpload, Action<UploadArtifact> action) {

        final UploadArtifact artifact = upload(projectId, toUpload);
        action.execute(artifact);
        return artifact;
    }

    /**
     * Disables automatic version detection for all artifacts published through the current task.
     */
    public void disableVersionDetection() {

        this.versionDetector.isEnabled = false;
    }

    /**
     * Add a version type provider to the task. This provider will be used to determine the valid version types for the
     * game.
     * @param providers The providers to add.
     */
    public void addVersionTypeProvider(VersionTypeProvider... providers) {
        this.versionTypeProviders.addAll(Arrays.asList(providers));
    }

    /**
     * Same as {@link #addVersionTypeProvider(VersionTypeProvider...)} but removes all existing providers first.
     */
    public void setVersionTypeProviders(VersionTypeProvider... providers) {
        this.versionTypeProviders.clear();
        this.addVersionTypeProvider(providers);
    }

    /**
     * This method is called when a gradle defined implementation of this task has been invoked. The project and the
     * task should already be configured at this point.
     */
    @TaskAction
    public void apply() {

        if (!this.uploadArtifacts.isEmpty()) {

            // The execution of this task is split into two steps.

            // The initialize step is used to validate the task configuration and request additional data from the API
            // that is required to process the configuration data into a format the API can understand.
            this.initialize();

            // The publishing step will iterate through all upload artifacts and publish them to CurseForge one by one.
            // The child files of an artifact will be uploaded after the parent artifact has been uploaded and the
            // upload response has been validated.
            this.publish();
        } else {

            this.log.warn("No upload artifacts were specified.");
        }
    }

    /**
     * Validates the task configuration and sets up data required for publishing artifacts.
     */
    private void initialize() {

        this.log.debug("Initializing upload task.");

        // An API token is required to publish a file.
        if (apiToken == null) {

            this.log.error("No API token was provided. The file could not be published!");
            throw new GradleException("Can not publish to CurseForge. No API token provided!");
        }

        this.log.debug("Task configured to connect to {}", this.apiEndpoint);

        // Request game version data from the API. This is used to map version slugs to API version IDs.
        this.validGameVersions = new GameVersions(
                parseString(this.apiEndpoint),
                projectDisplayName,
                this.getName(),
                this.versionTypeProviders
        );
        this.validGameVersions.refresh(parseString(this.apiToken));

        // Handle auto version detection.
        if (this.versionDetector.isEnabled) {

            this.versionDetector.detectVersions(this.validGameVersions);

            for (String detectedVersion : this.versionDetector.getDetectedVersions()) {

                for (UploadArtifact artifact : this.uploadArtifacts) {

                    artifact.addGameVersion(detectedVersion);
                }
            }
        }
    }

    /**
     * Attempts to publish all configured artifacts through the API.
     */
    private void publish() {

        final String tokenString = parseString(this.apiToken);
        final String endpointString = parseString(this.apiEndpoint);

        // Each artifact goes through two steps. The prepare step is used to process the artifact configuration into
        // a format accepted by the API. The second step is the upload step which posts an upload request to the API
        // and processes the response.
        for (UploadArtifact artifact : this.uploadArtifacts) {

            uploadArtifact(artifact, endpointString, tokenString);

            // Handle additional files, sometimes called sub files or child files.
            for (UploadArtifact childArtifact : artifact.getAdditionalArtifacts()) {

                uploadArtifact(childArtifact, endpointString, tokenString);
            }
        }
    }

    /**
     * Each artifact goes through two steps. The prepare step is used to process the artifact configuration into a
     * format accepted by the API. The second step is the upload step which posts an upload request to the API and
     * processes the response. If {@link #debugMode} is true, this second step will instead be replaced with logging.
     *
     * @param artifact Artifact being uploaded.
     * @param endpoint The endpoint to upload the file to.
     * @param token    The CurseForge API token used to authenticate the upload.
     */
    private void uploadArtifact(UploadArtifact artifact, String endpoint, String token) {

        artifact.prepareForUpload(this.validGameVersions);
        if (debugMode) {

            artifact.logUploadMetadata(endpoint);
        } else {

            artifact.beginUpload(endpoint, token);
        }
    }

    /**
     * Parses a long value from an object. This currently supports numbers and strings.
     *
     * @param obj The value to resolve.
     * @return The resolved Long value.
     */
    public static Long parseLong(Object obj) {

        if (obj instanceof Number) {

            return ((Number) obj).longValue();
        } else if (obj instanceof String) {

            return Long.parseLong((String) obj);
        }

        throw new GradleException("Could not parse long from " + obj.getClass().getName() + " of value " + obj);
    }

    /**
     * Gradle can be annoying and represent strings as non-string objects. This allows a variety of data types to be
     * accepted.
     *
     * @param obj The value to resolve.
     * @return The resolved value.
     */
    public static String parseString(Object obj) {

        if (obj instanceof Closure) {

            //Try to unwrap the closure. We do this before other checks such as if it is a file to allow processing
            // closures that return a file instead of only supporting ones that provide a string
            try {
                obj = ((Closure<?>) obj).call();
            } catch (Exception e) {

                throw new GradleException("Could not resolve closure as a string.", e);
            }
        }

        if (obj instanceof Provider<?>) {

            //Try to unwrap the Gradle provider. We do this before other checks such as if it is a file to allow processing
            // Providers that return a file instead of only supporting ones that provide a string
            try {
                obj = ((Provider<?>) obj).get();
            } catch (Exception e) {

                throw new GradleException("Could not resolve Provider as a string.", e);
            }
        }

        if (obj instanceof RegularFile) {
            obj = ((RegularFile) obj).getAsFile();
        }

        if (obj instanceof File) {

            try {
                return new String(Files.readAllBytes(((File) obj).toPath()), StandardCharsets.UTF_8);
            } catch (IOException e) {

                throw new GradleException("Could not parse File " + ((File) obj).getPath() + " as string.", e);
            }
        }

        return obj != null ? obj.toString() : null;
    }
}
