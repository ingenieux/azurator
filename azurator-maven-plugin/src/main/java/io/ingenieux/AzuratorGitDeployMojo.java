package io.ingenieux;

import java.io.File;
import java.util.List;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.context.Context;
import org.codehaus.plexus.context.ContextException;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Contextualizable;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

@Mojo(name = "fast-deploy", defaultPhase = LifecyclePhase.NONE)
public class AzuratorGitDeployMojo extends AbstractMojo implements Contextualizable {
  public class MavenSettingsCredentialsProvider extends UsernamePasswordCredentialsProvider {
    public MavenSettingsCredentialsProvider(Server server) {
      super(server.getUsername(), server.getPassword().toCharArray());
    }
  }

  /**
   * Application Name
   */
  @Parameter(property = "azurator.applicationName", defaultValue = "${project.artifactId}")
  String applicationName;

  /**
   * Application Name
   */
  @Parameter(property = "azurator.serverId", defaultValue = "azurewebsites")
  String serverId;

  /**
   * Artifact to Deploy
   */
  @Parameter(property = "azurator.sourceDirectory",
      defaultValue = "${project.build.directory}/${project.build.finalName}-bin")
  File sourceDirectory;

  /**
   * Maven Settings
   */
  @Parameter(defaultValue = "${settings}")
  private Settings settings;

  /**
   * Git Staging Dir (should not be under target/)
   */
  @Parameter(property = "azurator.stagingDirectory",
      defaultValue = "${project.basedir}/tmp-git-deployment-staging")
  File stagingDirectory;

  /**
   * Version Description
   */
  @Parameter(property = "azurator.versionDescription", defaultValue = "Update from fast-deploy")
  String versionDescription;

  public void execute() throws MojoExecutionException, MojoFailureException {
    try {
      executeInternal();
    } catch (Exception exc) {
      throw new MojoExecutionException("Failure", exc);
    }
  }

  protected Object executeInternal() throws Exception {
    Git git = getGitRepo();

    String commitId = null;

    Ref masterRef = git.getRepository().getRef("master");
    if (null != masterRef) {
      commitId = ObjectId.toString(masterRef.getObjectId());
    }

    Status status = git.status().call();

    {
      // Asks for Existing Files to get added
      git.add().setUpdate(true).addFilepattern(".").call();

      // Now as for any new files (untracked)

      AddCommand addCommand = git.add();

      if (!status.getUntracked().isEmpty()) {
        for (String s : status.getUntracked()) {
          getLog().info("Adding file " + s);
          addCommand.addFilepattern(s);
        }

        addCommand.call();
      }

      git.commit().setAll(true).setMessage(versionDescription).call();

      masterRef = git.getRepository().getRef("master");

      commitId = ObjectId.toString(masterRef.getObjectId());
    }

    String remote = getRemoteEndpoint();

    /*
     * Does the Push
     */
    {
      PushCommand cmd = git.//
          push();

      cmd.setProgressMonitor(new TextProgressMonitor());

      Iterable<PushResult> pushResults = null;
      try {
        pushResults = cmd.setRefSpecs(new RefSpec("HEAD:refs/heads/master")).//
            setForce(true).//
            setRemote(remote).//
            setCredentialsProvider(new MavenSettingsCredentialsProvider(getServer())).//
            call();
      } catch (Exception exc) {
        // Ignore
        getLog().warn("(Actually Expected) Exception", exc);
      }

      /*
       * I wish someday it could work... :(
       */
      if (null != pushResults) {
        for (PushResult pushResult : pushResults) {
          getLog().debug(" * " + pushResult.getMessages());
        }
      }
    }

    return null;
  }

  /**
   * Get server with given id
   *
   * @param settings
   * @param serverId must be non-null and non-empty
   * @return server or null if none matching
   */
  protected Server getServer(final Settings settings, final String serverId) {
    if (settings == null)
      return null;
    List<Server> servers = settings.getServers();
    if (servers == null || servers.isEmpty())
      return null;

    for (Server server : servers)
      if (serverId.equals(server.getId()))
        return server;
    return null;
  }

  private String getRemoteEndpoint() throws Exception {
    return String.format("https://%s.scm.azurewebsites.net:443/%s.git", this.applicationName, this.applicationName);
  }

  private Server getServer() throws ComponentLookupException {
    Server server = getServer(settings, serverId);
    SettingsDecrypter settingsDecrypter = container.lookup(SettingsDecrypter.class);
    SettingsDecryptionResult result =
        settingsDecrypter.decrypt(new DefaultSettingsDecryptionRequest(server));

    return result.getServer();
  }

  private Git getGitRepo() throws Exception {
    Git git = null;

    File gitRepo = stagingDirectory;
    Repository r = null;

    RepositoryBuilder b =
        new RepositoryBuilder().setGitDir(stagingDirectory).setWorkTree(sourceDirectory);

    if (!gitRepo.exists()) {
      gitRepo.getParentFile().mkdirs();

      r = b.build();

      r.create();
    } else {
      r = b.build();
    }

    return Git.wrap(r);
  }

  @Requirement
  private PlexusContainer container;

  /**
   * {@inheritDoc}
   */
  public void contextualize(Context context) throws ContextException {
    container = (PlexusContainer) context.get(PlexusConstants.PLEXUS_KEY);
  }
}
