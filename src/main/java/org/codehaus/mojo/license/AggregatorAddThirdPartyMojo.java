package org.codehaus.mojo.license;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.SortedMap;
import java.util.SortedSet;

/*
 * #%L
 * License Maven Plugin
 * %%
 * Copyright (C) 2008 - 2011 CodeLutin, Codehaus, Tony Chemit
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingException;
import org.codehaus.mojo.license.model.LicenseMap;
import org.codehaus.mojo.license.utils.SortedProperties;

/**
 * This goal forks executions of the add-third-party goal for all the leaf projects
 * of the tree of modules below the point where it is executed. Note that this
 * plugin sets a specific name, 'add-third-party', for the forked executions in the
 * individual projects. From command level, then
 * even though the execution of this goal is named 'default-cli', the forked executions
 * have the name 'add-third-party'. Thus, to use the <tt>pluginManagement</tt> element of
 * the POM to set options, you have to name the execution 'add-third-party',
 * not 'default-cli'.
 *
 * @author tchemit dev@tchemit.fr
 * @since 1.0
 */
@Mojo( name = "aggregate-add-third-party", aggregator = true, defaultPhase = LifecyclePhase.GENERATE_RESOURCES )
@Execute( goal = "add-third-party" )
public class AggregatorAddThirdPartyMojo extends AbstractAddThirdPartyMojo
{
    // ----------------------------------------------------------------------
    // Mojo Parameters
    // ----------------------------------------------------------------------

    /**
     * The projects in the reactor.
     *
     * @since 1.0
     */
    @Parameter( property = "reactorProjects", readonly = true, required = true )
    private List<?> reactorProjects;

    /**
     * To skip execution of this mojo.
     *
     * @since 1.5
     */
    @Parameter( property = "license.skipAggregateAddThirdParty", defaultValue = "false" )
    private boolean skipAggregateAddThirdParty;

    /**
     * To resolve third party licenses from an artifact.
     *
     * @since 1.11
     */
    @Parameter( property = "license.aggregateMissingLicensesFileArtifact" )
    private String aggregateMissingLicensesFileArtifact;

    /**
     * To resolve third party licenses from a file.
     *
     * @since 1.11
     */
    @Parameter( property = "license.aggregateMissingLicensesFile", defaultValue = "${project.basedir}/THIRD-PARTY.properties" )
    private File aggregateMissingLicensesFile;

    // ----------------------------------------------------------------------
    // AbstractLicenseMojo Implementaton
    // ----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isSkip()
    {
        return skipAggregateAddThirdParty;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean checkPackaging()
    {
        return acceptPackaging( "pom" );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean checkSkip()
    {
        if ( !isDoGenerate() && !isDoGenerateBundle() )
        {

            getLog().info( "All files are up to date, skip goal execution." );
            return false;
        }
        return super.checkSkip();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doAction()
            throws Exception
    {
        Log log = getLog();

        if ( isVerbose() )
        {
            log.info( "After executing on " + reactorProjects.size() + " project(s)" );
        }

        LicenseMap licenseMap = getLicenseMap();

        getLog().info( licenseMap.size() + " detected license(s)." );
        if ( isVerbose() )
        {
            for ( String id : licenseMap.keySet() )
            {
                getLog().info( " - " + id );
            }
        }

        if ( checkUnsafeDependencies() )
        {
            resolveUnsafeDependenciesFromFile( aggregateMissingLicensesFile );
        }

        if ( !StringUtils.isBlank( aggregateMissingLicensesFileArtifact ) && checkUnsafeDependencies() )
        {
            String[] tokens = StringUtils.split( aggregateMissingLicensesFileArtifact, ":" );
            if ( tokens.length != 3 )
            {
                throw new MojoFailureException(
                        "Invalid missing licenses artifact, you must specify groupId:artifactId:version "
                                + aggregateMissingLicensesFileArtifact );
            }
            String groupId = tokens[0];
            String artifactId = tokens[1];
            String version = tokens[2];

            resolveUnsafeDependenciesFromArtifact( groupId, artifactId, version );
        }

        overrideLicenses();

        boolean unsafe = checkUnsafeDependencies();

        boolean safeLicense = checkForbiddenLicenses();

        if ( !safeLicense && isFailIfWarning() )
        {
            throw new MojoFailureException( "There are some forbidden licenses used, please check your dependencies." );
        }
        writeThirdPartyFile();

        if ( unsafe && isFailIfWarning() )
        {
            throw new MojoFailureException( "There are some dependencies with no license, please review the modules." );
        }
    }

    // ----------------------------------------------------------------------
    // AbstractAddThirdPartyMojo Implementaton
    // ----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     */
    @Override
    protected SortedMap<String, MavenProject> loadDependencies()
    {
        // use the cache filled by modules in reactor
        return getHelper().getArtifactCache();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected SortedProperties createUnsafeMapping() throws ProjectBuildingException, IOException
    {

        String path =
            getMissingFile().getAbsolutePath().substring( getProject().getBasedir().getAbsolutePath().length() + 1 );

        if ( isVerbose() )
        {
            getLog().info( "Use missing file path : " + path );
        }

        SortedProperties unsafeMappings = new SortedProperties( getEncoding() );

        LicenseMap licenseMap = getLicenseMap();

        for ( Object o : reactorProjects )
        {
            MavenProject p = (MavenProject) o;

            File file = new File( p.getBasedir(), path );

            if ( file.exists() )
            {

                SortedProperties tmp = getHelper().loadUnsafeMapping( licenseMap, file, getProjectDependencies() );
                unsafeMappings.putAll( tmp );
            }

            SortedSet<MavenProject> unsafes = getHelper().getProjectsWithNoLicense( licenseMap );
            if ( CollectionUtils.isEmpty( unsafes ) )
            {

                // no more unsafe dependencies, can break
                break;
            }
        }
        return unsafeMappings;
    }

}
